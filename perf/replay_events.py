#!/usr/bin/env python3
import argparse
import json
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed


def send_one(base_url: str, topic: str, payload: dict, timeout: float):
    url = f"{base_url.rstrip('/')}/api/events/send?topic={topic}"
    req = urllib.request.Request(
        url=url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    started = time.perf_counter()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8", errors="replace")
        latency_ms = (time.perf_counter() - started) * 1000.0
        return resp.status, latency_ms, body


def percentile(values, p):
    if not values:
        return 0.0
    s = sorted(values)
    k = int(round((p / 100.0) * (len(s) - 1)))
    return s[k]


def main():
    parser = argparse.ArgumentParser(description="Replay NDJSON events to event-storage API")
    parser.add_argument("--file", default="perf/events.ndjson")
    parser.add_argument("--base-url", default="http://localhost:8082")
    parser.add_argument("--topic", default="events")
    parser.add_argument("--concurrency", type=int, default=16)
    parser.add_argument("--max-events", type=int, default=20000)
    parser.add_argument("--timeout", type=float, default=10.0)
    args = parser.parse_args()

    events = []
    with open(args.file, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            events.append(json.loads(line))
            if len(events) >= args.max_events:
                break

    ok = 0
    fail = 0
    lat = []
    started = time.perf_counter()

    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(send_one, args.base_url, args.topic, ev, args.timeout) for ev in events]
        for fut in as_completed(futures):
            try:
                status, latency_ms, _ = fut.result()
                lat.append(latency_ms)
                if 200 <= status < 300:
                    ok += 1
                else:
                    fail += 1
            except Exception:
                fail += 1

    elapsed = max(time.perf_counter() - started, 1e-9)
    print(f"Events attempted: {len(events)}")
    print(f"OK: {ok}, FAIL: {fail}")
    print(f"Elapsed: {elapsed:.2f}s")
    print(f"Throughput: {ok / elapsed:.2f} req/s")
    print(f"Latency p50: {percentile(lat, 50):.2f} ms")
    print(f"Latency p95: {percentile(lat, 95):.2f} ms")
    print(f"Latency p99: {percentile(lat, 99):.2f} ms")


if __name__ == "__main__":
    main()
