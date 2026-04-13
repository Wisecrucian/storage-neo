#!/usr/bin/env python3
"""
Test script that writes records with different RecordTypes,
varying volumes and attribute counts to create a realistic
uneven distribution on the dashboard.

Distribution:
  MESSAGE    ~45%  (bulk of traffic, 8-12 attrs, small payload)
  MODERATION ~20%  (moderation events, 15-25 attrs, larger payload)
  PURCHASE   ~15%  (purchase events, 5-8 attrs, numeric-heavy)
  SYSTEM     ~12%  (system/health events, 3-5 attrs, tiny)
  OTHER       ~8%  (misc, 10-20 attrs, varied)
"""
import json
import random
import string
import time
import urllib.request
from datetime import datetime, timedelta, timezone

API = "http://localhost:8081/api/records/save-batch"
TOTAL_RECORDS = 20_000
BATCH_SIZE = 500

DISTRIBUTION = [
    ("MESSAGE",    0.45),
    ("MODERATION", 0.20),
    ("PURCHASE",   0.15),
    ("SYSTEM",     0.12),
    ("OTHER",      0.08),
]

LANGS = ["ru", "en", "de", "fr", "zh", "ja", "ko", "es"]
SOURCES = ["mobile", "web", "api", "bot", "desktop"]
CAMPAIGNS = [f"cmp-{i}" for i in range(30)]


def random_string(length):
    return "".join(random.choices(string.ascii_lowercase + string.digits, k=length))


def make_attrs(record_type):
    attrs = {}

    if record_type == "MESSAGE":
        attrs["source"] = random.choice(SOURCES)
        attrs["lang"] = random.choice(LANGS)
        attrs["score"] = random.randint(0, 100)
        attrs["text_len"] = random.randint(5, 2000)
        attrs["has_media"] = random.choice([True, False])
        attrs["channel"] = random.choice(["direct", "group", "broadcast"])
        attrs["device_os"] = random.choice(["ios", "android", "web"])
        for i in range(random.randint(0, 4)):
            attrs[f"tag_{i}"] = random_string(6)

    elif record_type == "MODERATION":
        attrs["source"] = random.choice(SOURCES)
        attrs["lang"] = random.choice(LANGS)
        attrs["score"] = random.randint(50, 100)
        attrs["reason"] = random.choice(["spam", "abuse", "nsfw", "scam", "harassment", "illegal"])
        attrs["severity"] = random.choice(["low", "medium", "high", "critical"])
        attrs["auto_flagged"] = random.choice([True, False])
        attrs["reviewer_id"] = random.randint(1, 50)
        attrs["text_snippet"] = random_string(random.randint(20, 80))
        attrs["rule_id"] = f"rule-{random.randint(1, 200)}"
        attrs["confidence"] = round(random.uniform(0.5, 1.0), 3)
        for i in range(random.randint(5, 15)):
            attrs[f"ctx_{i}"] = random_string(random.randint(3, 12))

    elif record_type == "PURCHASE":
        attrs["source"] = random.choice(SOURCES)
        attrs["lang"] = random.choice(LANGS[:3])
        attrs["score"] = 0
        attrs["amount"] = round(random.uniform(0.99, 9999.99), 2)
        attrs["currency"] = random.choice(["USD", "EUR", "RUB", "GBP"])
        attrs["product_id"] = f"prod-{random.randint(1, 500)}"
        attrs["campaign"] = random.choice(CAMPAIGNS)

    elif record_type == "SYSTEM":
        attrs["source"] = "system"
        attrs["component"] = random.choice(["db", "cache", "api-gw", "worker", "scheduler"])
        attrs["level"] = random.choice(["info", "warn", "error"])

    else:
        attrs["source"] = random.choice(SOURCES)
        attrs["lang"] = random.choice(LANGS)
        attrs["score"] = random.randint(0, 50)
        for i in range(random.randint(6, 16)):
            attrs[f"extra_{i}"] = random.choice([
                random.randint(0, 10000),
                random_string(random.randint(5, 20)),
                round(random.uniform(0, 100), 2),
            ])

    return attrs


def make_record(record_type):
    now = datetime.now(timezone.utc)
    event_time = now - timedelta(seconds=random.randint(0, 1800))
    return {
        "recordType": record_type,
        "eventTime": event_time.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "ingestTime": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "eventType": record_type,
        "eventId": f"{record_type[:3].lower()}-{random.randint(100000, 999999)}",
        "userId": random.randint(1000, 50000),
        "chatId": random.randint(100, 5000),
        "messageId": random.randint(1_000_000, 9_000_000),
        "attrs": make_attrs(record_type),
    }


def generate_batch(size):
    records = []
    for _ in range(size):
        r = random.random()
        cumulative = 0
        chosen = "OTHER"
        for rt, prob in DISTRIBUTION:
            cumulative += prob
            if r < cumulative:
                chosen = rt
                break
        records.append(make_record(chosen))
    return records


def send_batch(records):
    data = json.dumps(records).encode("utf-8")
    req = urllib.request.Request(API, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode())


def main():
    print(f"Generating and sending {TOTAL_RECORDS} records in batches of {BATCH_SIZE}")
    print(f"Distribution: {', '.join(f'{rt}={p*100:.0f}%' for rt, p in DISTRIBUTION)}")
    print()

    sent = 0
    type_counts = {}
    attr_sizes = {}
    start = time.time()

    while sent < TOTAL_RECORDS:
        batch_sz = min(BATCH_SIZE, TOTAL_RECORDS - sent)
        batch = generate_batch(batch_sz)

        for rec in batch:
            rt = rec["recordType"]
            type_counts[rt] = type_counts.get(rt, 0) + 1
            ac = len(rec["attrs"])
            if rt not in attr_sizes:
                attr_sizes[rt] = []
            attr_sizes[rt].append(ac)

        try:
            result = send_batch(batch)
            sent += batch_sz
            elapsed = time.time() - start
            rate = sent / elapsed if elapsed > 0 else 0
            print(f"  [{sent:>6}/{TOTAL_RECORDS}] {result}  ({rate:.0f} rec/s)")
        except Exception as e:
            print(f"  ERROR: {e}")
            time.sleep(1)

    elapsed = time.time() - start
    print(f"\nDone in {elapsed:.1f}s ({sent/elapsed:.0f} rec/s)")
    print(f"\nRecords by type:")
    for rt, cnt in sorted(type_counts.items(), key=lambda x: -x[1]):
        avg_attrs = sum(attr_sizes[rt]) / len(attr_sizes[rt])
        min_a = min(attr_sizes[rt])
        max_a = max(attr_sizes[rt])
        print(f"  {rt:>12}: {cnt:>6} ({cnt/sent*100:5.1f}%)  attrs: avg={avg_attrs:.1f} min={min_a} max={max_a}")

    print("\nNow check Grafana: http://localhost:3000  (admin/admin)")
    print("Dashboard: Event Storage > Event Storage Business")


if __name__ == "__main__":
    main()
