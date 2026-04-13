import json, urllib.request, time, threading, sys
from concurrent.futures import ThreadPoolExecutor

BASE = "http://localhost:8082"
CH = "http://localhost:8123"
COUNT_PER_REQUEST = 10000
DURATION_SEC = 120
COOLDOWN_SEC = 15
THREAD_CONFIGS = [1, 4, 8, 12]

results = []

def http_post(url, data=b'', timeout=120):
    req = urllib.request.Request(url=url, method='POST', data=data)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode('utf-8', 'replace')

def http_get(url, timeout=60):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.read().decode('utf-8', 'replace')

def ch_query(sql, timeout=30):
    req = urllib.request.Request(url=CH, method='POST', data=sql.encode('utf-8'))
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode('utf-8', 'replace').strip()

def run_write_benchmark(threads, duration):
    ok = 0
    fail = 0
    total_gen = 0
    lats = []
    lock = threading.Lock()
    stop = time.time() + duration

    def worker():
        nonlocal ok, fail, total_gen
        while time.time() < stop:
            t0 = time.perf_counter()
            try:
                body = http_post(f"{BASE}/api/records/generate-and-save?count={COUNT_PER_REQUEST}")
                j = json.loads(body)
                gen = j.get('generated', 0)
                dt = (time.perf_counter() - t0) * 1000
                with lock:
                    ok += 1
                    total_gen += gen
                    lats.append(dt)
            except Exception as e:
                with lock:
                    fail += 1
                    lats.append((time.perf_counter() - t0) * 1000)

    with ThreadPoolExecutor(max_workers=threads) as ex:
        for _ in range(threads):
            ex.submit(worker)

    lats.sort()
    def pct(p):
        if not lats: return None
        idx = int(round((p / 100) * (len(lats) - 1)))
        return round(lats[idx], 1)

    throughput = total_gen / duration if duration > 0 else 0
    return {
        "threads": threads,
        "duration_sec": duration,
        "requests_ok": ok,
        "requests_fail": fail,
        "total_records": total_gen,
        "throughput_rec_per_sec": round(throughput, 1),
        "lat_ms_p50": pct(50),
        "lat_ms_p95": pct(95),
        "lat_ms_p99": pct(99),
    }

def run_read_test():
    read_results = {}
    try:
        t0 = time.perf_counter()
        body = http_get(f"{BASE}/api/records/moderation?userId=5000&limit=10")
        read_results["moderation_ms"] = round((time.perf_counter() - t0) * 1000, 1)
        read_results["moderation_rows"] = len(json.loads(body))
    except Exception as e:
        read_results["moderation_error"] = str(e)
    try:
        t0 = time.perf_counter()
        body = http_get(f"{BASE}/api/records/analytics/run?scenario=record_type_mix&limit=10")
        read_results["analytics_ms"] = round((time.perf_counter() - t0) * 1000, 1)
    except Exception as e:
        read_results["analytics_error"] = str(e)
    return read_results

def collect_ch_stats():
    stats = {}
    try:
        stats["total_rows"] = int(ch_query("SELECT count() FROM default.events_raw"))
    except: stats["total_rows"] = "error"
    try:
        raw = ch_query("SELECT sum(bytes_on_disk), sum(data_uncompressed_bytes), sum(data_compressed_bytes), count() FROM system.parts WHERE table='events_raw' AND active FORMAT JSONEachRow")
        j = json.loads(raw)
        stats["disk_bytes"] = int(j.get("sum(bytes_on_disk)", 0))
        stats["uncompressed_bytes"] = int(j.get("sum(data_uncompressed_bytes)", 0))
        stats["compressed_bytes"] = int(j.get("sum(data_compressed_bytes)", 0))
        stats["active_parts"] = int(j.get("count()", 0))
        if stats["compressed_bytes"] > 0:
            stats["compression_ratio"] = round(stats["uncompressed_bytes"] / stats["compressed_bytes"], 2)
        stats["disk_mb"] = round(stats["disk_bytes"] / 1024 / 1024, 1)
        stats["uncompressed_mb"] = round(stats["uncompressed_bytes"] / 1024 / 1024, 1)
        if stats["total_rows"] and isinstance(stats["total_rows"], int) and stats["total_rows"] > 0:
            stats["bytes_per_row_compressed"] = round(stats["compressed_bytes"] / stats["total_rows"], 1)
            stats["bytes_per_row_uncompressed"] = round(stats["uncompressed_bytes"] / stats["total_rows"], 1)
    except Exception as e:
        stats["parts_error"] = str(e)
    return stats

print("=" * 60)
print("SINGLE-SHARD CLICKHOUSE BENCHMARK")
print(f"batch per request: {COUNT_PER_REQUEST}, duration per run: {DURATION_SEC}s")
print("=" * 60)

all_results = []

for threads in THREAD_CONFIGS:
    print(f"\n--- Write benchmark: {threads} thread(s), {DURATION_SEC}s ---")
    wr = run_write_benchmark(threads, DURATION_SEC)
    print(json.dumps(wr, indent=2))

    print(f"  Cooldown {COOLDOWN_SEC}s...")
    time.sleep(COOLDOWN_SEC)

    print(f"  Read test after {threads}-thread write...")
    rd = run_read_test()
    print(json.dumps(rd, indent=2))

    all_results.append({"write": wr, "read_after": rd})

print("\n--- ClickHouse Storage Stats ---")
stats = collect_ch_stats()
print(json.dumps(stats, indent=2))

summary = {"benchmark_runs": all_results, "ch_storage_stats": stats}

with open("/Users/max/Downloads/ITMO/вкр/benchmark_results.json", "w") as f:
    json.dump(summary, f, indent=2, ensure_ascii=False)

print("\nResults saved to benchmark_results.json")
print("=" * 60)
