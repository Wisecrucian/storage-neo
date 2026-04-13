#!/usr/bin/env python3
"""
Systematic benchmark runner for scaling research.
Tests throughput under varying batch sizes and thread counts.
"""
import json, random, string, threading, time, urllib.request, subprocess, sys
from datetime import datetime, timezone

BASE = "http://localhost:8081"
CH   = "http://localhost:8123"
TYPES = ['MESSAGE', 'MODERATION', 'PURCHASE', 'SYSTEM', 'OTHER']

def rand_str(n): return ''.join(random.choices(string.ascii_lowercase, k=n))

def make_batch(size):
    now = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    return [{
        'recordType': random.choice(TYPES),
        'eventType': 'bench',
        'userId': random.randint(1, 1_000_000),
        'chatId': random.randint(1, 100_000),
        'messageId': random.randint(1, 10_000_000),
        'eventTime': now,
        'attrs': {'lang': random.choice(['ru','en','zh','fr','ko']),
                  'source': random.choice(['bot','web','api']),
                  'score': str(random.randint(0, 100)),
                  'k': rand_str(6)}
    } for _ in range(size)]

def run_bench(threads, batch_size, duration=30):
    stats = {'recs': 0, 'err': 0, 'latencies': []}
    lock = threading.Lock()

    def worker(stop):
        while not stop.is_set():
            try:
                t0 = time.monotonic()
                data = json.dumps(make_batch(batch_size)).encode()
                req = urllib.request.Request(
                    f'{BASE}/api/records/save-batch', data=data,
                    headers={'Content-Type': 'application/json'})
                urllib.request.urlopen(req, timeout=20)
                elapsed_ms = (time.monotonic() - t0) * 1000
                with lock:
                    stats['recs'] += batch_size
                    stats['latencies'].append(elapsed_ms)
            except Exception:
                with lock: stats['err'] += 1

    stop = threading.Event()
    ts = [threading.Thread(target=worker, args=(stop,)) for _ in range(threads)]
    [t.start() for t in ts]
    time.sleep(duration)
    stop.set()
    [t.join() for t in ts]

    lats = sorted(stats['latencies'])
    n = len(lats)
    return {
        'threads': threads,
        'batch_size': batch_size,
        'duration_s': duration,
        'total_records': stats['recs'],
        'throughput_rps': stats['recs'] / duration,
        'errors': stats['err'],
        'batches': n,
        'lat_p50_ms': lats[int(n * 0.50)] if n else 0,
        'lat_p95_ms': lats[int(n * 0.95)] if n else 0,
        'lat_p99_ms': lats[int(n * 0.99)] if n else 0,
        'lat_avg_ms': sum(lats) / n if n else 0,
    }

def ch_query(sql):
    req = urllib.request.Request(CH, data=sql.encode(), headers={'Content-Type': 'text/plain'})
    return urllib.request.urlopen(req, timeout=10).read().decode().strip()

def kafka_lag():
    try:
        out = subprocess.check_output(
            ['docker', 'exec', 'event-storage-kafka', 'kafka-consumer-groups',
             '--bootstrap-server', 'localhost:9092',
             '--describe', '--group', 'clickhouse-events-consumer'],
            stderr=subprocess.DEVNULL).decode()
        total_lag = 0
        for line in out.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 6 and parts[5].isdigit():
                total_lag += int(parts[5])
        return total_lag
    except Exception:
        return -1

def run_read_bench(duration=20):
    """Measure moderation and analytics query latency."""
    mod_lats, ana_lats = [], []
    stop = threading.Event()

    def mod_worker():
        while not stop.is_set():
            uid = random.randint(1, 1_000_000)
            rt = random.choice(TYPES)
            t0 = time.monotonic()
            try:
                req = urllib.request.Request(
                    f'{BASE}/api/records/moderation?userId={uid}&recordType={rt}&limit=20')
                urllib.request.urlopen(req, timeout=10)
                mod_lats.append((time.monotonic() - t0) * 1000)
            except Exception: pass
            time.sleep(0.05)

    def ana_worker():
        scenarios = ['events_by_type_timeseries', 'record_type_mix', 'top_users_by_volume']
        while not stop.is_set():
            s = random.choice(scenarios)
            t0 = time.monotonic()
            try:
                req = urllib.request.Request(f'{BASE}/api/records/analytics/run?scenario={s}&limit=100')
                urllib.request.urlopen(req, timeout=30)
                ana_lats.append((time.monotonic() - t0) * 1000)
            except Exception: pass
            time.sleep(0.5)

    threads = [threading.Thread(target=mod_worker) for _ in range(2)]
    threads.append(threading.Thread(target=ana_worker))
    [t.start() for t in threads]
    time.sleep(duration)
    stop.set()
    [t.join() for t in threads]

    def pct(lst, p):
        s = sorted(lst)
        return s[int(len(s)*p)] if s else 0

    return {
        'moderation_count': len(mod_lats),
        'moderation_p50_ms': pct(mod_lats, 0.5),
        'moderation_p95_ms': pct(mod_lats, 0.95),
        'moderation_avg_ms': sum(mod_lats)/len(mod_lats) if mod_lats else 0,
        'analytics_count': len(ana_lats),
        'analytics_p50_ms': pct(ana_lats, 0.5),
        'analytics_p95_ms': pct(ana_lats, 0.95),
        'analytics_avg_ms': sum(ana_lats)/len(ana_lats) if ana_lats else 0,
    }

if __name__ == '__main__':
    results = {}
    WARMUP = 10
    DURATION = 30

    print("=" * 70)
    print("EXPERIMENT 1: Throughput vs Batch Size  (threads=8, fixed)")
    print("=" * 70)
    batch_results = []
    for bs in [500, 1000, 2000, 5000]:
        print(f"\n  batch_size={bs}, threads=8 — warmup {WARMUP}s...")
        run_bench(8, bs, WARMUP)  # warmup
        print(f"  Measuring {DURATION}s...")
        r = run_bench(8, bs, DURATION)
        batch_results.append(r)
        lag = kafka_lag()
        print(f"  → {r['throughput_rps']:>8,.0f} rec/s | p50={r['lat_p50_ms']:.0f}ms p95={r['lat_p95_ms']:.0f}ms | lag={lag}")
    results['batch_size_series'] = batch_results

    print("\n" + "=" * 70)
    print("EXPERIMENT 2: Throughput vs Thread Count  (batch=2000, fixed)")
    print("=" * 70)
    thread_results = []
    for tc in [1, 2, 4, 8, 16, 32]:
        print(f"\n  threads={tc}, batch=2000 — warmup {WARMUP}s...")
        run_bench(tc, 2000, WARMUP)
        print(f"  Measuring {DURATION}s...")
        r = run_bench(tc, 2000, DURATION)
        thread_results.append(r)
        lag = kafka_lag()
        print(f"  → {r['throughput_rps']:>8,.0f} rec/s | p50={r['lat_p50_ms']:.0f}ms p95={r['lat_p95_ms']:.0f}ms | lag={lag}")
    results['thread_count_series'] = thread_results

    print("\n" + "=" * 70)
    print("EXPERIMENT 3: Read latency under zero write load")
    print("=" * 70)
    print("  Measuring reads idle (20s)...")
    r_idle = run_read_bench(20)
    results['read_idle'] = r_idle
    print(f"  Moderation: p50={r_idle['moderation_p50_ms']:.0f}ms p95={r_idle['moderation_p95_ms']:.0f}ms (n={r_idle['moderation_count']})")
    print(f"  Analytics:  p50={r_idle['analytics_p50_ms']:.0f}ms p95={r_idle['analytics_p95_ms']:.0f}ms (n={r_idle['analytics_count']})")

    print("\n" + "=" * 70)
    print("EXPERIMENT 4: Read latency under max write load (16 threads x 2000)")
    print("=" * 70)
    print("  Starting write load...")
    write_stop = threading.Event()

    def bg_writer(stop):
        while not stop.is_set():
            try:
                data = json.dumps(make_batch(2000)).encode()
                req = urllib.request.Request(f'{BASE}/api/records/save-batch', data=data,
                    headers={'Content-Type': 'application/json'})
                urllib.request.urlopen(req, timeout=20)
            except Exception: pass

    bg_threads = [threading.Thread(target=bg_writer, args=(write_stop,)) for _ in range(16)]
    [t.start() for t in bg_threads]
    time.sleep(5)  # let writes stabilize
    print("  Measuring reads under load (20s)...")
    r_load = run_read_bench(20)
    write_stop.set()
    [t.join() for t in bg_threads]
    results['read_under_load'] = r_load
    print(f"  Moderation: p50={r_load['moderation_p50_ms']:.0f}ms p95={r_load['moderation_p95_ms']:.0f}ms (n={r_load['moderation_count']})")
    print(f"  Analytics:  p50={r_load['analytics_p50_ms']:.0f}ms p95={r_load['analytics_p95_ms']:.0f}ms (n={r_load['analytics_count']})")

    print("\n" + "=" * 70)
    print("EXPERIMENT 5: CH storage state after all tests")
    print("=" * 70)
    rows   = ch_query("SELECT count() FROM default.events_raw FORMAT TSV")
    disk   = ch_query("SELECT formatReadableSize(sum(data_compressed_bytes)) FROM system.parts WHERE table='events_raw' AND active=1 FORMAT TSV")
    ratio  = ch_query("SELECT round(sum(data_uncompressed_bytes)/greatest(sum(data_compressed_bytes),1),2) FROM system.parts WHERE table='events_raw' AND active=1 FORMAT TSV")
    parts  = ch_query("SELECT count() FROM system.parts WHERE table='events_raw' AND active=1 FORMAT TSV")
    lag    = kafka_lag()
    results['storage'] = {'rows': rows, 'disk': disk, 'ratio': ratio, 'parts': parts, 'kafka_lag': lag}
    print(f"  Rows:   {rows}")
    print(f"  Disk:   {disk}  (ratio {ratio}x)")
    print(f"  Parts:  {parts}")
    print(f"  Kafka lag: {lag}")

    # Save results
    with open('/Users/max/Downloads/ITMO/вкр/bench_results.json', 'w') as f:
        json.dump(results, f, indent=2)
    print("\n\nResults saved to bench_results.json")
    print("=" * 70)
