#!/usr/bin/env python3
import argparse
import json
import os
import random
import threading
import time
import urllib.request
import uuid
from datetime import datetime, timezone


def percentile(values, p):
    if not values:
        return 0.0
    s = sorted(values)
    idx = int(round((p / 100.0) * (len(s) - 1)))
    return s[idx]


class Metrics:
    def __init__(self):
        self.lock = threading.Lock()
        self.ok = 0
        self.fail = 0
        self.latencies_ms = []

    def add(self, ok, latency_ms):
        with self.lock:
            if ok:
                self.ok += 1
            else:
                self.fail += 1
            self.latencies_ms.append(latency_ms)


def build_payload(payload_kb):
    blob_size = max(payload_kb * 1024 - 350, 0)
    blob = "x" * blob_size
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": random.choice(["login", "logout", "purchase", "click", "view"]),
        "userId": f"user-{random.randint(1, 100000)}",
        "sessionId": f"sess-{random.randint(1, 1000000)}",
        "ipAddress": f"10.{random.randint(0,255)}.{random.randint(0,255)}.{random.randint(1,254)}",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "payloadBlob": blob,
    }


def worker(stop_event, base_url, topic, timeout, payload_kb, metrics):
    url = f"{base_url.rstrip('/')}/api/events/send?topic={topic}"
    while not stop_event.is_set():
        payload = build_payload(payload_kb)
        started = time.perf_counter()
        ok = False
        try:
            req = urllib.request.Request(
                url=url,
                data=json.dumps(payload).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                ok = 200 <= resp.status < 300
        except Exception:
            ok = False
        latency_ms = (time.perf_counter() - started) * 1000.0
        metrics.add(ok, latency_ms)


def run_stage(name, duration_sec, concurrency, base_url, topic, timeout, payload_kb):
    print(f"[stage:{name}] duration={duration_sec}s concurrency={concurrency} payload={payload_kb}KB")
    stop_event = threading.Event()
    metrics = Metrics()
    threads = []
    for _ in range(concurrency):
        t = threading.Thread(
            target=worker,
            args=(stop_event, base_url, topic, timeout, payload_kb, metrics),
            daemon=True,
        )
        t.start()
        threads.append(t)

    started = time.perf_counter()
    time.sleep(duration_sec)
    stop_event.set()
    for t in threads:
        t.join(timeout=1.0)
    elapsed = max(time.perf_counter() - started, 1e-9)
    attempted = metrics.ok + metrics.fail
    result = {
        "stage": name,
        "duration_sec": duration_sec,
        "concurrency": concurrency,
        "payload_kb": payload_kb,
        "attempted": attempted,
        "ok": metrics.ok,
        "fail": metrics.fail,
        "throughput_rps": metrics.ok / elapsed,
        "p50_ms": percentile(metrics.latencies_ms, 50),
        "p95_ms": percentile(metrics.latencies_ms, 95),
        "p99_ms": percentile(metrics.latencies_ms, 99),
    }
    print(json.dumps(result, ensure_ascii=False))
    return result


def profile_stages(profile):
    if profile == "heavy":
        return [
            {"name": "warmup", "duration_sec": 60, "concurrency": 20, "payload_kb": 1},
            {"name": "baseline", "duration_sec": 180, "concurrency": 50, "payload_kb": 2},
            {"name": "peak", "duration_sec": 180, "concurrency": 100, "payload_kb": 2},
            {"name": "soak", "duration_sec": 600, "concurrency": 40, "payload_kb": 1},
        ]
    if profile == "extreme":
        return [
            {"name": "warmup", "duration_sec": 60, "concurrency": 30, "payload_kb": 1},
            {"name": "high", "duration_sec": 240, "concurrency": 120, "payload_kb": 2},
            {"name": "spike", "duration_sec": 120, "concurrency": 200, "payload_kb": 4},
            {"name": "recover", "duration_sec": 180, "concurrency": 60, "payload_kb": 1},
            {"name": "soak", "duration_sec": 1200, "concurrency": 50, "payload_kb": 1},
        ]
    # default medium
    return [
        {"name": "warmup", "duration_sec": 45, "concurrency": 10, "payload_kb": 1},
        {"name": "load", "duration_sec": 120, "concurrency": 30, "payload_kb": 1},
        {"name": "peak", "duration_sec": 120, "concurrency": 60, "payload_kb": 2},
    ]


def main():
    parser = argparse.ArgumentParser(description="Staged heavy load runner for event-storage")
    parser.add_argument("--base-url", default="http://localhost:8082")
    parser.add_argument("--topic", default="events")
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--profile", choices=["medium", "heavy", "extreme"], default="heavy")
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    stages = profile_stages(args.profile)
    results = []
    for s in stages:
        results.append(
            run_stage(
                s["name"],
                s["duration_sec"],
                s["concurrency"],
                args.base_url,
                args.topic,
                args.timeout,
                s["payload_kb"],
            )
        )

    total_ok = sum(r["ok"] for r in results)
    total_fail = sum(r["fail"] for r in results)
    summary = {
        "profile": args.profile,
        "total_ok": total_ok,
        "total_fail": total_fail,
        "stages": results,
        "generated_at": datetime.now(timezone.utc).isoformat(),
    }
    print("SUMMARY:")
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    out = args.output
    if not out:
        os.makedirs("perf/results", exist_ok=True)
        out = f"perf/results/stress_{args.profile}_{int(time.time())}.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    print(f"Saved results to: {out}")


if __name__ == "__main__":
    main()
