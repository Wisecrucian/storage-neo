#!/usr/bin/env python3
"""
Mixed workload test: simultaneous writes, point reads, and analytical queries.
Runs 3 thread groups in parallel for a configurable duration.

  Writers:      continuous batch writes (varied record types)
  Point readers: single-record lookups by user_id / record_type
  Analytical:   aggregation queries (record_type_mix, timeseries, top_users)
"""
import json
import random
import string
import threading
import time
import urllib.request
from datetime import datetime, timedelta, timezone

BASE = "http://localhost:8081"
DURATION_SEC = 120
WRITE_BATCH_SIZE = 500
WRITE_THREADS = 2
POINT_READ_THREADS = 2
ANALYTICAL_THREADS = 1
WRITE_DELAY = 0.0
POINT_READ_DELAY = 0.05
ANALYTICAL_DELAY = 0.3

RECORD_TYPES = ["MESSAGE", "MODERATION", "PURCHASE", "SYSTEM", "OTHER"]
WEIGHTS = [0.45, 0.20, 0.15, 0.12, 0.08]
LANGS = ["ru", "en", "de", "fr", "zh"]
SOURCES = ["mobile", "web", "api", "bot"]

stats_lock = threading.Lock()
stats = {
    "writes_ok": 0, "writes_err": 0, "writes_records": 0,
    "point_reads_ok": 0, "point_reads_err": 0,
    "analytical_ok": 0, "analytical_err": 0,
}


def pick_type():
    r = random.random()
    c = 0
    for rt, w in zip(RECORD_TYPES, WEIGHTS):
        c += w
        if r < c:
            return rt
    return "OTHER"


def rand_str(n):
    return "".join(random.choices(string.ascii_lowercase + string.digits, k=n))


def make_record():
    rt = pick_type()
    now = datetime.now(timezone.utc)
    attrs = {"source": random.choice(SOURCES), "lang": random.choice(LANGS), "score": random.randint(0, 100)}
    if rt == "MODERATION":
        attrs["reason"] = random.choice(["spam", "abuse", "nsfw", "scam"])
        attrs["severity"] = random.choice(["low", "medium", "high"])
        for i in range(random.randint(5, 15)):
            attrs[f"ctx_{i}"] = rand_str(8)
    elif rt == "PURCHASE":
        attrs["amount"] = round(random.uniform(1, 5000), 2)
        attrs["currency"] = random.choice(["USD", "EUR", "RUB"])
    elif rt == "MESSAGE":
        attrs["text_len"] = random.randint(5, 2000)
        attrs["channel"] = random.choice(["direct", "group"])
        for i in range(random.randint(0, 5)):
            attrs[f"tag_{i}"] = rand_str(5)
    elif rt == "SYSTEM":
        attrs["component"] = random.choice(["db", "cache", "api-gw"])
        attrs["level"] = random.choice(["info", "warn", "error"])

    return {
        "recordType": rt,
        "eventTime": (now - timedelta(seconds=random.randint(0, 900))).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "ingestTime": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "eventType": rt,
        "eventId": f"{rt[:3].lower()}-{random.randint(100000, 999999)}",
        "userId": random.randint(1000, 50000),
        "chatId": random.randint(100, 5000),
        "messageId": random.randint(1_000_000, 9_000_000),
        "attrs": attrs,
    }


def post_json(url, data, timeout=30):
    body = json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def get_json(url, timeout=30):
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def writer_loop(thread_id, stop_event):
    while not stop_event.is_set():
        batch = [make_record() for _ in range(WRITE_BATCH_SIZE)]
        try:
            post_json(f"{BASE}/api/records/save-batch", batch)
            with stats_lock:
                stats["writes_ok"] += 1
                stats["writes_records"] += len(batch)
        except Exception:
            with stats_lock:
                stats["writes_err"] += 1
        if WRITE_DELAY > 0:
            time.sleep(WRITE_DELAY)


def point_reader_loop(thread_id, stop_event):
    while not stop_event.is_set():
        uid = random.randint(1000, 50000)
        rt = random.choice(RECORD_TYPES)
        limit = random.choice([1, 5, 10, 20])
        url = f"{BASE}/api/records/moderation?userId={uid}&recordType={rt}&limit={limit}"
        try:
            get_json(url)
            with stats_lock:
                stats["point_reads_ok"] += 1
        except Exception:
            with stats_lock:
                stats["point_reads_err"] += 1
        time.sleep(POINT_READ_DELAY)


def analytical_loop(thread_id, stop_event):
    scenarios = ["events_by_type_timeseries", "record_type_mix", "top_users_by_volume", "attrs_source_lang_breakdown"]
    while not stop_event.is_set():
        scenario = random.choice(scenarios)
        limit = random.choice([50, 100, 500])
        url = f"{BASE}/api/records/analytics/run?scenario={scenario}&limit={limit}"
        try:
            get_json(url)
            with stats_lock:
                stats["analytical_ok"] += 1
        except Exception:
            with stats_lock:
                stats["analytical_err"] += 1
        time.sleep(ANALYTICAL_DELAY)


def progress_reporter(stop_event, start_time):
    while not stop_event.is_set():
        time.sleep(10)
        elapsed = time.time() - start_time
        with stats_lock:
            s = dict(stats)
        wr = s["writes_records"] / elapsed if elapsed > 0 else 0
        pr = s["point_reads_ok"] / elapsed if elapsed > 0 else 0
        ar = s["analytical_ok"] / elapsed if elapsed > 0 else 0
        remaining = max(0, DURATION_SEC - elapsed)
        print(f"  [{elapsed:5.0f}s / {DURATION_SEC}s]  "
              f"writes={s['writes_records']} ({wr:.0f}/s) "
              f"point_reads={s['point_reads_ok']} ({pr:.1f}/s) "
              f"analytics={s['analytical_ok']} ({ar:.1f}/s) "
              f"errors=w:{s['writes_err']} pr:{s['point_reads_err']} a:{s['analytical_err']}  "
              f"remaining={remaining:.0f}s")


def main():
    print(f"=== Mixed Workload Test ===")
    print(f"Duration:     {DURATION_SEC}s")
    print(f"Writers:      {WRITE_THREADS} threads × {WRITE_BATCH_SIZE} records/batch")
    print(f"Point reads:  {POINT_READ_THREADS} threads, delay {POINT_READ_DELAY}s")
    print(f"Analytical:   {ANALYTICAL_THREADS} threads, delay {ANALYTICAL_DELAY}s")
    print()

    stop = threading.Event()
    threads = []

    for i in range(WRITE_THREADS):
        t = threading.Thread(target=writer_loop, args=(i, stop), daemon=True)
        threads.append(t)
    for i in range(POINT_READ_THREADS):
        t = threading.Thread(target=point_reader_loop, args=(i, stop), daemon=True)
        threads.append(t)
    for i in range(ANALYTICAL_THREADS):
        t = threading.Thread(target=analytical_loop, args=(i, stop), daemon=True)
        threads.append(t)

    reporter = threading.Thread(target=progress_reporter, args=(stop, time.time()), daemon=True)

    start = time.time()
    print(f"Starting {len(threads)} threads...")
    for t in threads:
        t.start()
    reporter.start()

    try:
        while time.time() - start < DURATION_SEC:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nInterrupted!")

    stop.set()
    for t in threads:
        t.join(timeout=5)

    elapsed = time.time() - start
    print(f"\n=== Results ({elapsed:.1f}s) ===")
    print(f"  Writes:       {stats['writes_records']:>8} records  ({stats['writes_records']/elapsed:.0f}/s)  batches_ok={stats['writes_ok']}  err={stats['writes_err']}")
    print(f"  Point reads:  {stats['point_reads_ok']:>8} queries  ({stats['point_reads_ok']/elapsed:.1f}/s)  err={stats['point_reads_err']}")
    print(f"  Analytical:   {stats['analytical_ok']:>8} queries  ({stats['analytical_ok']/elapsed:.1f}/s)  err={stats['analytical_err']}")
    total_ops = stats['writes_ok'] + stats['point_reads_ok'] + stats['analytical_ok']
    print(f"  Total ops:    {total_ops:>8} ({total_ops/elapsed:.1f}/s)")
    print(f"\nCheck dashboards: http://localhost:3000  (admin/admin)")
    print(f"  - Event Storage Benchmark  (mixed workload overlay)")
    print(f"  - Event Storage Business   (record type breakdown)")


if __name__ == "__main__":
    main()
