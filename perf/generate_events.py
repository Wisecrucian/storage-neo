#!/usr/bin/env python3
import argparse
import csv
import json
import random
import uuid
from datetime import datetime, timezone


def build_event(i: int):
    event_type = random.choice(["login", "logout", "purchase", "click", "view"])
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": event_type,
        "userId": f"user-{random.randint(1, 5000)}",
        "sessionId": f"sess-{random.randint(1, 100000)}",
        "ipAddress": f"10.{random.randint(0,255)}.{random.randint(0,255)}.{random.randint(1,254)}",
        "amount": round(random.uniform(1, 5000), 2) if event_type == "purchase" else None,
        "source": "offline-perf",
        "index": i,
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }


def main():
    parser = argparse.ArgumentParser(description="Generate offline events dataset")
    parser.add_argument("--count", type=int, default=50000, help="Number of events to generate")
    parser.add_argument("--ndjson", default="perf/events.ndjson", help="Path to NDJSON output")
    parser.add_argument("--csv", default="perf/events.csv", help="Path to CSV output")
    args = parser.parse_args()

    with open(args.ndjson, "w", encoding="utf-8") as ndj, open(args.csv, "w", newline="", encoding="utf-8") as cf:
        writer = csv.DictWriter(
            cf,
            fieldnames=["eventId", "eventType", "userId", "sessionId", "ipAddress", "amount", "source", "index", "timestamp"],
        )
        writer.writeheader()
        for i in range(args.count):
            event = build_event(i)
            ndj.write(json.dumps(event, ensure_ascii=False) + "\n")
            writer.writerow(event)

    print(f"Generated {args.count} events:")
    print(f"  NDJSON: {args.ndjson}")
    print(f"  CSV:    {args.csv}")


if __name__ == "__main__":
    main()
