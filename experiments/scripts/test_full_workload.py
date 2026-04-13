"""
Mixed workload load test — full stack.

Threads:
  WRITE        × 3  — POST /api/records/save-batch (500 rec each)
  READ_USER    × 2  — SELECT by user_id          (uses primary index)
  READ_CHAT    × 2  — SELECT by chat_id           (uses bloom_filter)
  READ_ATTR    × 1  — GROUP BY attr_lang          (uses mat. column)
  READ_OLAP    × 1  — GROUP BY record_type+time   (uses projection by_record_type)

Duration: configurable, default 90s
"""

import http.client
import json
import random
import threading
import time
from collections import defaultdict

# ── Config ──────────────────────────────────────────────────────────────
DURATION_SEC   = 120
APP_HOST       = "localhost"
APP_PORT       = 8081
CH_HOST        = "localhost"
CH_PORT        = 8123
BATCH_SIZE     = 500

RECORD_TYPES   = ["MESSAGE", "MODERATION", "PURCHASE", "SYSTEM", "OTHER"]
WEIGHTS        = [45, 20, 15, 12, 8]
LANGS          = ["ru", "en", "de", "fr"]
SOURCES        = ["web", "ios", "android"]
# user_id 1-200, chat_id 1-500 — соответствуют залитым данным
KNOWN_USER_IDS = list(range(1, 201))
KNOWN_CHAT_IDS = list(range(1, 501))

# ── Shared state ─────────────────────────────────────────────────────────
lock      = threading.Lock()
stop_flag = threading.Event()

stats = defaultdict(lambda: {"ok": 0, "err": 0, "latencies": []})


def record(kind, latency_ms, ok=True):
    with lock:
        if ok:
            stats[kind]["ok"] += 1
            stats[kind]["latencies"].append(latency_ms)
        else:
            stats[kind]["err"] += 1


# ── Helpers ──────────────────────────────────────────────────────────────
def make_record():
    rt = random.choices(RECORD_TYPES, WEIGHTS)[0]
    return {
        "eventTime":  time.strftime("%Y-%m-%dT%H:%M:%SZ",
                                    time.gmtime(time.time() - random.randint(0, 3600))),
        "recordType": rt,
        "userId":     random.choice(KNOWN_USER_IDS),
        "chatId":     random.choice(KNOWN_CHAT_IDS),
        "messageId":  random.randint(1, 10_000_000),
        "attrs": {
            "lang":    random.choice(LANGS),
            "source":  random.choice(SOURCES),
            "payload": "x" * random.randint(20, 150),
        },
    }


def app_post(path, body_bytes):
    conn = http.client.HTTPConnection(APP_HOST, APP_PORT, timeout=15)
    conn.request("POST", path, body=body_bytes,
                 headers={"Content-Type": "application/json",
                          "Content-Length": str(len(body_bytes))})
    resp = conn.getresponse()
    resp.read()
    conn.close()
    return resp.status


def ch_query(sql):
    conn = http.client.HTTPConnection(CH_HOST, CH_PORT, timeout=15)
    body = sql.encode()
    conn.request("POST", "/", body=body,
                 headers={"Content-Type": "text/plain",
                          "Content-Length": str(len(body))})
    resp = conn.getresponse()
    data = resp.read()
    conn.close()
    return resp.status, data


# ── Thread workers ────────────────────────────────────────────────────────
def writer():
    while not stop_flag.is_set():
        batch = [make_record() for _ in range(BATCH_SIZE)]
        body  = json.dumps(batch).encode()
        t0    = time.time()
        try:
            code = app_post("/api/records/save-batch", body)
            ms   = (time.time() - t0) * 1000
            record("write", ms, ok=(code == 200))
        except Exception:
            record("write", 0, ok=False)


def reader_user():
    """Point read: WHERE user_id = X AND record_type = Y — primary index"""
    while not stop_flag.is_set():
        uid = random.choice(KNOWN_USER_IDS)
        rt  = random.choice(RECORD_TYPES)
        sql = (f"SELECT count(), min(event_time), max(event_time) "
               f"FROM default.events_raw "
               f"WHERE user_id={uid} AND record_type='{rt}' FORMAT JSONEachRow")
        t0 = time.time()
        try:
            code, _ = ch_query(sql)
            ms = (time.time() - t0) * 1000
            record("read_user", ms, ok=(code == 200))
        except Exception:
            record("read_user", 0, ok=False)
        time.sleep(0.05)


def reader_chat():
    """Point read: WHERE chat_id = X — bloom_filter skip index"""
    while not stop_flag.is_set():
        cid = random.choice(KNOWN_CHAT_IDS)
        sql = (f"SELECT count(), min(event_time), max(event_time) "
               f"FROM default.events_raw "
               f"WHERE chat_id={cid} FORMAT JSONEachRow")
        t0 = time.time()
        try:
            code, _ = ch_query(sql)
            ms = (time.time() - t0) * 1000
            record("read_chat", ms, ok=(code == 200))
        except Exception:
            record("read_chat", 0, ok=False)
        time.sleep(0.05)


def reader_attr():
    """Aggregation: GROUP BY attr_lang — materialized column"""
    while not stop_flag.is_set():
        lang = random.choice(LANGS)
        sql  = (f"SELECT attr_lang, count() AS cnt, uniq(user_id) AS users "
                f"FROM default.events_raw "
                f"WHERE attr_lang='{lang}' "
                f"GROUP BY attr_lang FORMAT JSONEachRow")
        t0 = time.time()
        try:
            code, _ = ch_query(sql)
            ms = (time.time() - t0) * 1000
            record("read_attr", ms, ok=(code == 200))
        except Exception:
            record("read_attr", 0, ok=False)
        time.sleep(0.2)


def reader_olap():
    """Analytics: GROUP BY record_type — projection by_record_type"""
    while not stop_flag.is_set():
        sql = ("SELECT record_type, "
               "  toStartOfMinute(event_time) AS minute, "
               "  count() AS cnt "
               "FROM default.events_raw "
               "WHERE event_time >= now() - INTERVAL 1 DAY "
               "GROUP BY record_type, minute "
               "ORDER BY minute DESC LIMIT 50 FORMAT JSONEachRow")
        t0 = time.time()
        try:
            code, _ = ch_query(sql)
            ms = (time.time() - t0) * 1000
            record("read_olap", ms, ok=(code == 200))
        except Exception:
            record("read_olap", 0, ok=False)
        time.sleep(1.0)


# ── Parts monitor ─────────────────────────────────────────────────────────
parts_history = []

def parts_monitor():
    while not stop_flag.is_set():
        try:
            _, data = ch_query(
                "SELECT count() AS p, sum(rows) AS r "
                "FROM system.parts WHERE table='events_raw' AND active "
                "FORMAT JSONEachRow"
            )
            d = json.loads(data)
            with lock:
                parts_history.append({"t": time.time(), "parts": d["p"], "rows": d["r"]})
        except Exception:
            pass
        time.sleep(5)


# ── Report ────────────────────────────────────────────────────────────────
def percentile(lst, p):
    if not lst:
        return 0
    s = sorted(lst)
    idx = int(len(s) * p / 100)
    return round(s[min(idx, len(s)-1)], 1)


def print_report(elapsed):
    print(f"\n{'='*60}")
    print(f"  Mixed workload report  ({elapsed:.0f}s)")
    print(f"{'='*60}")

    labels = {
        "write":     "WRITE (→ Kafka → CH)",
        "read_user": "READ user_id  (primary index)",
        "read_chat": "READ chat_id  (bloom filter)",
        "read_attr": "READ attr_lang (mat. column)",
        "read_olap": "OLAP  record_type (projection)",
    }

    for kind, label in labels.items():
        s  = stats[kind]
        ok = s["ok"]
        er = s["err"]
        lats = s["latencies"]
        rps  = round(ok / elapsed, 1)
        print(f"\n  {label}")
        print(f"    OK={ok}  ERR={er}  rate={rps}/s")
        if lats:
            print(f"    latency p50={percentile(lats,50)}ms  "
                  f"p95={percentile(lats,95)}ms  "
                  f"p99={percentile(lats,99)}ms  "
                  f"max={round(max(lats),1)}ms")

    # Write throughput in records/s
    w = stats["write"]
    rec_rate = round(w["ok"] * BATCH_SIZE / elapsed)
    print(f"\n  WRITE record throughput: {rec_rate} rec/s  "
          f"({w['ok']} batches × {BATCH_SIZE})")

    # Parts evolution
    if parts_history:
        p0 = parts_history[0]["parts"]
        pr = parts_history[-1]["rows"]
        pm = max(h["parts"] for h in parts_history)
        print(f"\n  Parts: start={p0}  peak={pm}  "
              f"final rows={pr:,}")

    print(f"\n{'='*60}\n")


# ── Main ──────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    threads = []

    # Writers
    for _ in range(3):
        threads.append(threading.Thread(target=writer, daemon=True))

    # Readers
    for _ in range(2):
        threads.append(threading.Thread(target=reader_user, daemon=True))
    for _ in range(2):
        threads.append(threading.Thread(target=reader_chat, daemon=True))
    threads.append(threading.Thread(target=reader_attr,  daemon=True))
    threads.append(threading.Thread(target=reader_olap,  daemon=True))

    # Monitor
    threads.append(threading.Thread(target=parts_monitor, daemon=True))

    print(f"Starting {DURATION_SEC}s mixed workload "
          f"(3 writers, 2 user-read, 2 chat-read, 1 attr-read, 1 olap)...")

    t_start = time.time()
    for t in threads:
        t.start()

    while time.time() - t_start < DURATION_SEC:
        elapsed = time.time() - t_start
        with lock:
            w_ok  = stats["write"]["ok"]
            ru_ok = stats["read_user"]["ok"]
            rc_ok = stats["read_chat"]["ok"]
        print(f"  [{elapsed:5.0f}s] writes={w_ok*BATCH_SIZE:,}recs  "
              f"read_user={ru_ok}  read_chat={rc_ok}", end="\r")
        time.sleep(2)

    stop_flag.set()
    print_report(time.time() - t_start)
