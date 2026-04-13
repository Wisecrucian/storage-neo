#!/usr/bin/env python3
"""
EXPERIMENTS_PHASE2 orchestrator.
Results -> experiments_phase2_out.json + EXPERIMENTS_PHASE2.md

Перед запуском: подняты ClickHouse :8123, Java :8081, Kafka :9092
  cd event-storage && docker compose up -d
"""
from __future__ import annotations

import json
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

# Schema-aware generator lives next to this file.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from storage_schema_sim import SchemaAwareRecordGenerator  # noqa: E402

CH = "http://localhost:8123"
APP = "http://localhost:8081"
BATCH = 2000

# Thread-local generator: each writer thread gets its own RNG state so
# they don't serialize on a shared lock.  seed=None → non-deterministic,
# which is correct for load generation.
_tls = threading.local()

def _thread_gen() -> SchemaAwareRecordGenerator:
    if not hasattr(_tls, "gen"):
        _tls.gen = SchemaAwareRecordGenerator(seed=None)
    return _tls.gen

stop_writers = threading.Event()
write_lock = threading.Lock()
write_ok = 0
write_err = 0
_recent_errors: list[str] = []


def log_err(msg: str) -> None:
    with write_lock:
        _recent_errors.append(msg)
        if len(_recent_errors) > 15:
            del _recent_errors[:-15]


def ch(q: str, timeout: float = 300) -> str:
    req = urllib.request.Request(CH + "/", data=q.encode(), method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode()


def ch_json_row(q: str) -> dict:
    line = ch(q).strip().split("\n")[0]
    return json.loads(line)


def percentile(xs: list[float], p: float) -> float:
    if not xs:
        return 0.0
    xs = sorted(xs)
    k = min(max(int(len(xs) * p / 100), 0), len(xs) - 1)
    return round(xs[k], 2)


def summ_ms(ms: list[float]) -> dict:
    return {
        "avg_ms": round(statistics.mean(ms), 2) if ms else 0,
        "p50_ms": percentile(ms, 50),
        "p95_ms": percentile(ms, 95),
        "p99_ms": percentile(ms, 99),
    }


def make_batch(n: int) -> list[dict]:
    """
    Schema-aware batch generator.  Uses StorageRecord / UserActivitySchema model
    from storage_schema_sim.py.

    Applied appendData semantics:
      - records sorted ascending by TIMESTAMP (sort_by_ts=True),
      - no dedup (skip_duplicates=False) — identical to real high-throughput path,
      - no capacity limit — batches are small enough to never hit maxCapacity.

    Each record has all required attributes (TYPE, TIMESTAMP, USER_ID) plus
    the attribute set defined for its UserActivityType.  Values are typed and
    validated.  attrs keys are lowercase ClickHouse-compatible (e.g. 'lang',
    'source', 'terminal_type', 'org_hash').
    """
    return _thread_gen().make_api_batch(n, sort_by_ts=True, skip_duplicates=False)


def preflight() -> None:
    errors: list[str] = []
    try:
        p = urllib.request.urlopen(CH + "/ping", timeout=5)
        if b"Ok" not in p.read():
            errors.append("ClickHouse /ping unexpected")
    except Exception as e:
        errors.append(f"ClickHouse unreachable: {e}")

    try:
        urllib.request.urlopen(APP + "/actuator/health", timeout=5)
    except Exception as e:
        errors.append(f"Java API unreachable: {e}")

    body = json.dumps(
        [
            {
                "eventTime": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "recordType": "SYSTEM",
                "userId": 1,
                "chatId": 0,
                "messageId": 0,
                "attrs": {
                    "type": "REGISTRATION",
                    "lang": "ru",
                    "source": "web",
                    "terminal_type": "ONEME",
                    "org_hash": "1136490941434531770",
                },
            }
        ]
    ).encode()
    req = urllib.request.Request(
        APP + "/api/records/save-batch",
        data=body,
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        t0 = time.time()
        with urllib.request.urlopen(req, timeout=45) as r:
            r.read()
        if (time.time() - t0) > 30:
            errors.append("save-batch very slow (>30s) — проверьте Kafka: docker compose ps kafka")
    except urllib.error.HTTPError as e:
        errors.append(f"save-batch HTTP {e.code}: {e.read()[:200]!r}")
    except Exception as e:
        errors.append(
            f"save-batch failed ({e}). Часто: Kafka остановлен — "
            f"cd event-storage && docker compose up -d kafka"
        )

    if errors:
        print("PREFLIGHT FAILED:", file=sys.stderr)
        for e in errors:
            print(" ", e, file=sys.stderr)
        sys.exit(1)
    print("Preflight OK (CH + API + save-batch)")


def writer_thread_throttled(sleep_sec: float) -> None:
    global write_ok, write_err
    while not stop_writers.is_set():
        body = json.dumps(make_batch(BATCH)).encode()
        ok_batch = False
        last_err = ""
        for attempt in range(3):
            req = urllib.request.Request(
                APP + "/api/records/save-batch",
                data=body,
                method="POST",
                headers={"Content-Type": "application/json"},
            )
            try:
                with urllib.request.urlopen(req, timeout=120) as r:
                    r.read()
                    code = r.getcode()
                if code == 200:
                    with write_lock:
                        write_ok += BATCH
                    ok_batch = True
                    break
                last_err = f"HTTP {code}"
                if code in (500, 502, 503, 504) and attempt < 2:
                    time.sleep(0.4 * (attempt + 1))
                    continue
                with write_lock:
                    write_err += 1
                log_err(last_err)
                break
            except urllib.error.HTTPError as e:
                last_err = f"HTTP {e.code}"
                err_body = e.read()[:80]
                if e.code in (500, 502, 503, 504) and attempt < 2:
                    time.sleep(0.4 * (attempt + 1))
                    continue
                with write_lock:
                    write_err += 1
                log_err(f"{last_err} {err_body!r}")
                break
            except Exception as e:
                last_err = str(e)[:120]
                with write_lock:
                    write_err += 1
                log_err(last_err)
                break
        if sleep_sec > 0:
            time.sleep(sleep_sec)


def run_writers_throttled(threads: int, sleep_sec: float, seconds: float) -> tuple[float, int, int]:
    global write_ok, write_err
    stop_writers.clear()
    with write_lock:
        write_ok = 0
        write_err = 0
    th = [
        threading.Thread(target=writer_thread_throttled, args=(sleep_sec,), daemon=True)
        for _ in range(max(0, threads))
    ]
    t0 = time.time()
    for t in th:
        t.start()
    time.sleep(seconds)
    stop_writers.set()
    for t in th:
        t.join(timeout=15)
    elapsed = time.time() - t0
    with write_lock:
        ok, err = write_ok, write_err
    rps = ok / elapsed if elapsed > 0 else 0.0
    return rps, ok, err


def writer_thread() -> None:
    writer_thread_throttled(0.0)


def run_writers(threads: int, seconds: float) -> tuple[float, int, int]:
    return run_writers_throttled(threads, 0.0, seconds)


def tune_throttle_for_target(target_rps: int) -> tuple[int, float, float]:
    """
    Подбирает (threads, sleep_sec): больший sleep -> ниже RPS.
    Бинарный поиск по sleep в [0, hi]; для каждого числа потоков — отдельный поиск.
    Возвращает (threads, sleep_sec, achieved_rps на последней короткой проверке).
    """
    if target_rps <= 0:
        return 0, 0.0, 0.0

    best_thr, best_sleep, best_rps = 1, 0.0, 0.0
    best_diff = 1e18

    for thr in range(1, 5):
        lo, hi = 0.0, 0.65
        for _ in range(8):
            mid = (lo + hi) / 2
            ach, _, _ = run_writers_throttled(thr, mid, 10.0)
            if ach > target_rps * 1.04:
                lo = mid
            else:
                hi = mid
        ach_f, _, _ = run_writers_throttled(thr, hi, 8.0)
        diff = abs(ach_f - target_rps)
        if diff < best_diff:
            best_diff = diff
            best_thr, best_sleep, best_rps = thr, hi, ach_f
        time.sleep(1)

    return best_thr, round(best_sleep, 4), round(best_rps, 0)


def measure_reads(point_n: int = 28, olap_n: int = 10) -> dict:
    point_ms, olap_ms = [], []
    uid, rt = 42, "MESSAGE"
    for _ in range(point_n):
        t0 = time.time()
        ch(
            f"SELECT count() FROM default.events_raw WHERE user_id={uid} AND record_type='{rt}' FORMAT JSONEachRow"
        )
        point_ms.append((time.time() - t0) * 1000)
        time.sleep(0.04)
    for _ in range(olap_n):
        t0 = time.time()
        ch(
            "SELECT record_type, toStartOfHour(event_time) h, count() "
            "FROM default.events_raw WHERE event_time >= now() - INTERVAL 2 DAY "
            "GROUP BY record_type, h ORDER BY h DESC LIMIT 40 FORMAT JSONEachRow"
        )
        olap_ms.append((time.time() - t0) * 1000)
        time.sleep(0.08)
    return {
        "point_read": summ_ms(point_ms),
        "olap_read": summ_ms(olap_ms),
    }


def parts_snapshot() -> dict:
    row = ch_json_row(
        "SELECT "
        "count() AS parts, "
        "sum(rows) AS rows, "
        "sum(data_compressed_bytes) AS comp, "
        "sum(data_uncompressed_bytes) AS uncomp "
        "FROM system.parts WHERE database='default' AND table='events_raw' AND active "
        "FORMAT JSONEachRow"
    )
    comp = int(row["comp"])
    uncomp = int(row["uncomp"])
    ratio = round(uncomp / comp, 3) if comp else 0.0
    return {
        "active_parts": int(row["parts"]),
        "rows": int(row["rows"]),
        "compression_ratio": ratio,
    }


def ensure_attr_source() -> None:
    ch(
        "ALTER TABLE default.events_raw "
        "ADD COLUMN IF NOT EXISTS attr_source LowCardinality(String) MATERIALIZED attrs['source']"
    )
    time.sleep(2)


def calibrate_threads() -> list[dict]:
    out = []
    for thr in [1, 2, 3, 4]:
        rps, ok, err = run_writers(thr, 40)
        out.append({"threads": thr, "rec_s": round(rps, 0), "written": ok, "http_errors": err})
        if ok == 0 and err > 0:
            out[-1]["hint"] = "0 записей — см. _recent_errors или Kafka"
        time.sleep(5)
    return out


def exp1_read_degradation(cal: list[dict]) -> dict:
    global write_ok, write_err
    targets = [0, 5000, 10000, 15000, 20000]
    rows: list[dict] = []
    throttle_plan: list[dict] = []

    for tgt in targets:
        if tgt == 0:
            time.sleep(2)
            lat = measure_reads()
            snap = parts_snapshot()
            rows.append(
                {
                    "target_write_rec_s": tgt,
                    "writer_threads": 0,
                    "sleep_sec": 0.0,
                    "achieved_write_rec_s": 0,
                    **lat,
                    **snap,
                }
            )
            continue

        thr, sleep_sec, tuned_rps = tune_throttle_for_target(tgt)
        throttle_plan.append(
            {
                "target": tgt,
                "threads": thr,
                "sleep_sec": sleep_sec,
                "calibration_achieved_rec_s": tuned_rps,
            }
        )

        stop_writers.clear()
        with write_lock:
            write_ok = 0
            write_err = 0
        wth = [
            threading.Thread(target=writer_thread_throttled, args=(sleep_sec,), daemon=True)
            for _ in range(thr)
        ]
        for wt in wth:
            wt.start()
        time.sleep(35)
        with write_lock:
            w0 = write_ok
        time.sleep(10)
        with write_lock:
            w1 = write_ok
        achieved = (w1 - w0) / 10.0 if w1 >= w0 else 0.0
        lat = measure_reads()
        snap = parts_snapshot()
        stop_writers.set()
        for wt in wth:
            wt.join(timeout=20)
        with write_lock:
            errs = write_err
        rows.append(
            {
                "target_write_rec_s": tgt,
                "writer_threads": thr,
                "sleep_sec": sleep_sec,
                "achieved_write_rec_s": round(achieved, 0),
                "write_http_errors_window": errs,
                **lat,
                **snap,
            }
        )
        time.sleep(8)

    return {"calibration": cal, "throttle_plan": throttle_plan, "rows": rows}


def exp2_parts_timeline() -> dict:
    global write_ok
    samples: list[dict] = []
    stop_writers.clear()
    with write_lock:
        write_ok = 0
    wth = [threading.Thread(target=writer_thread, daemon=True) for _ in range(4)]
    for wt in wth:
        wt.start()
    t_end = time.time() + 300
    while time.time() < t_end:
        snap = parts_snapshot()
        with write_lock:
            w = write_ok
        samples.append({"phase": "write_20k_approx", "written_so_far": w, **snap})
        time.sleep(10)
    stop_writers.set()
    for wt in wth:
        wt.join(timeout=20)
    idle_samples = []
    t_idle = time.time() + 120
    while time.time() < t_idle:
        idle_samples.append({"phase": "idle", **parts_snapshot()})
        time.sleep(10)
    return {"write_phase": samples, "idle_phase": idle_samples}


def exp3_projection_insert() -> dict:
    results: dict[str, Any] = {}

    def measure() -> dict:
        rps, ok, err = run_writers(4, 30)
        return {
            "throughput_rec_s": round(rps, 0),
            "records_ok": ok,
            "http_errors": err,
            "nonzero": ok > 0,
        }

    results["with_projection"] = measure()
    ch("ALTER TABLE default.events_raw DROP PROJECTION IF EXISTS by_record_type")
    time.sleep(5)
    results["without_projection"] = measure()
    ch(
        "ALTER TABLE default.events_raw ADD PROJECTION IF NOT EXISTS by_record_type "
        "(SELECT * ORDER BY record_type, event_time)"
    )
    time.sleep(3)
    ch("ALTER TABLE default.events_raw MATERIALIZE PROJECTION by_record_type")
    time.sleep(15)
    return results


def exp4_map_vs_mat() -> dict:
    ensure_attr_source()
    time.sleep(3)

    def ten_times(sql: str) -> dict:
        ms = []
        for _ in range(10):
            t0 = time.time()
            ch(sql + " FORMAT JSONEachRow")
            ms.append((time.time() - t0) * 1000)
        return summ_ms(ms)

    return {
        "group_lang_map": ten_times("SELECT attrs['lang'] k, count() FROM default.events_raw GROUP BY k"),
        "group_lang_mat": ten_times("SELECT attr_lang k, count() FROM default.events_raw GROUP BY k"),
        "group_source_map": ten_times(
            "SELECT attrs['source'] k, count() FROM default.events_raw GROUP BY k"
        ),
        "group_source_mat": ten_times("SELECT attr_source k, count() FROM default.events_raw GROUP BY k"),
        "filter_lang_map": ten_times(
            "SELECT count() FROM default.events_raw WHERE attrs['lang']='ru'"
        ),
        "filter_lang_mat": ten_times("SELECT count() FROM default.events_raw WHERE attr_lang='ru'"),
    }


def explain_subset(sql: str, max_chars: int = 1200) -> str:
    return ch("EXPLAIN indexes=1\n" + sql + "\nFORMAT TSVRaw")[:max_chars]


def exp5_bloom() -> dict:
    """Сначала замер С индексом (как в таблице), затем DROP — без индекса, затем восстановление."""
    row = ch_json_row(
        "SELECT chat_id AS c FROM default.events_raw GROUP BY chat_id ORDER BY count() DESC LIMIT 1 "
        "FORMAT JSONEachRow"
    )
    cid = int(row["c"])
    sql = f"SELECT count() FROM default.events_raw WHERE chat_id={cid}"
    with_ms: list[float] = []
    for _ in range(10):
        t0 = time.time()
        ch(sql + " FORMAT JSONEachRow")
        with_ms.append((time.time() - t0) * 1000)
    ex_with = explain_subset(sql)

    ch("ALTER TABLE default.events_raw DROP INDEX IF EXISTS idx_chat_id")
    time.sleep(3)
    no_ms: list[float] = []
    for _ in range(10):
        t0 = time.time()
        ch(sql + " FORMAT JSONEachRow")
        no_ms.append((time.time() - t0) * 1000)
    ex_no = explain_subset(sql)

    ch(
        "ALTER TABLE default.events_raw ADD INDEX IF NOT EXISTS idx_chat_id chat_id "
        "TYPE bloom_filter(0.01) GRANULARITY 4"
    )
    ch("ALTER TABLE default.events_raw MATERIALIZE INDEX idx_chat_id")
    time.sleep(20)
    return {
        "chat_id": cid,
        "with_bloom": {**summ_ms(with_ms), "explain_excerpt": ex_with},
        "without_bloom": {**summ_ms(no_ms), "explain_excerpt": ex_no},
    }


def exp6_kafka_vs_direct() -> dict:
    k_rps, k_ok, k_err = run_writers(4, 30)
    time.sleep(5)
    t0 = time.time()
    total_rows = 0
    while time.time() - t0 < 30:
        ch(
            "INSERT INTO default.events_raw SELECT "
            "now() - toIntervalSecond(rand() % 86400), now(), "
            "['MESSAGE','MODERATION','PURCHASE','SYSTEM','OTHER'][1+rand()%5], "
            "generateUUIDv7(), rand()%500+1, rand()%800+1, rand()%10000000, "
            "map('lang',['ru','en','de','fr'][1+rand()%4],"
            "'source',['web','ios','android'][1+rand()%3],"
            "'payload', repeat('x', 20+rand()%100)) "
            "FROM numbers(120000)",
            timeout=180,
        )
        total_rows += 120000
    d_rps = total_rows / 30.0
    return {
        "kafka_java_api": {
            "throughput_rec_s": round(k_rps, 0),
            "records": k_ok,
            "http_errors": k_err,
            "nonzero": k_ok > 0,
        },
        "direct_http_insert_ch": {
            "throughput_rec_s": round(d_rps, 0),
            "rows_inserted_approx": total_rows,
            "note": "INSERT SELECT into events_raw, bypass Kafka",
        },
    }


# Тяжёлые OLAP: широкое окно, крупные агрегации, FORMAT Null — без раздувания ответа по сети.
_HEAVY_OLAP_SQLS = (
    "SELECT record_type, toStartOfHour(event_time) AS h, "
    "ifNull(nullIf(attrs['lang'], ''), '_') AS lang, count() AS c, "
    "uniqCombined64(user_id) AS u, max(length(attrs['payload'])) AS mx "
    "FROM default.events_raw "
    "WHERE event_time >= now() - INTERVAL 30 DAY "
    "GROUP BY record_type, h, lang ORDER BY c DESC LIMIT 25000 FORMAT Null",
    "SELECT user_id % 256 AS bucket, record_type, toDate(event_time) AS d, "
    "count() AS c, avg(length(attrs['payload'])) AS avg_pl "
    "FROM default.events_raw "
    "WHERE event_time >= now() - INTERVAL 30 DAY "
    "GROUP BY bucket, record_type, d ORDER BY c DESC LIMIT 60000 FORMAT Null",
    "SELECT x.record_type, sum(x.c) AS sc FROM ( "
    "SELECT record_type, user_id, count() AS c FROM default.events_raw "
    "WHERE event_time >= now() - INTERVAL 14 DAY "
    "GROUP BY record_type, user_id ) AS x "
    "GROUP BY x.record_type ORDER BY sc DESC LIMIT 100000 FORMAT Null",
)


def exp7_high_write_parallel_heavy_olap(
    duration_sec: float = 600.0,
    writer_threads: int = 4,
    analytic_interval_sec: float = 12.0,
) -> dict:
    """
    Непрерывная запись на максимальном RPS (sleep=0) + редкие тяжёлые запросы к CH параллельно.
    По умолчанию 600 с (~10 мин) смешанной нагрузки.
    """
    global write_ok, write_err

    snap_before = parts_snapshot()
    stop_olap = threading.Event()
    olap_runs: list[dict] = []
    t_start = time.time()

    def olap_worker() -> None:
        idx = 0
        while not stop_olap.is_set():
            if stop_olap.wait(timeout=analytic_interval_sec):
                break
            sql = _HEAVY_OLAP_SQLS[idx % len(_HEAVY_OLAP_SQLS)]
            idx += 1
            t0 = time.time()
            rel = round(t0 - t_start, 2)
            try:
                ch(sql, timeout=600)
                ms = (time.time() - t0) * 1000
                olap_runs.append(
                    {
                        "at_elapsed_s": rel,
                        "query_index": idx,
                        "latency_ms": round(ms, 2),
                        "ok": True,
                    }
                )
            except Exception as e:
                olap_runs.append(
                    {
                        "at_elapsed_s": rel,
                        "query_index": idx,
                        "ok": False,
                        "error": str(e)[:240],
                    }
                )

    stop_writers.clear()
    with write_lock:
        write_ok = 0
        write_err = 0
    writers = [
        threading.Thread(target=writer_thread_throttled, args=(0.0,), daemon=True)
        for _ in range(max(1, writer_threads))
    ]
    for w in writers:
        w.start()
    ot = threading.Thread(target=olap_worker, daemon=True)
    ot.start()

    time.sleep(duration_sec)

    stop_olap.set()
    stop_writers.set()
    ot.join(timeout=620)
    for w in writers:
        w.join(timeout=45)

    elapsed = time.time() - t_start
    with write_lock:
        ok, n_err = write_ok, write_err

    snap_after = parts_snapshot()
    ok_ms = [float(r["latency_ms"]) for r in olap_runs if r.get("ok")]
    return {
        "config": {
            "duration_sec": duration_sec,
            "writer_threads": writer_threads,
            "write_sleep_sec": 0.0,
            "heavy_olap_interval_sec": analytic_interval_sec,
            "heavy_olap_variants": len(_HEAVY_OLAP_SQLS),
        },
        "parts_before": snap_before,
        "parts_after": snap_after,
        "wall_s": round(elapsed, 2),
        "write_throughput_rec_s": round(ok / elapsed, 0) if elapsed > 0 else 0.0,
        "records_written": ok,
        "write_http_error_batches": n_err,
        "heavy_olap_runs": olap_runs,
        "heavy_olap_latency": summ_ms(ok_ms),
        "heavy_olap_failures": sum(1 for r in olap_runs if not r.get("ok")),
        "heavy_olap_completed": len(ok_ms),
    }


def render_markdown(data: dict) -> str:
    lines = [
        "# EXPERIMENTS PHASE 2 (auto)",
        "",
        f"_Generated: {data.get('finished', '')}_",
        "",
        "## Exp1 throttle plan",
        "",
        "```json",
        json.dumps(data.get("exp1_read_degradation", {}).get("throttle_plan", []), indent=2, ensure_ascii=False),
        "```",
        "",
        "## Tables",
        "",
    ]
    exp1 = data.get("exp1_read_degradation", {})
    if exp1.get("rows"):
        lines.append("### Exp1 read degradation")
        lines.append(
            "| Target | Thr | sleep | Achieved rec/s | Point p50/p95 | OLAP p50/p95 | Parts | Ratio |"
        )
        lines.append("|---:|---:|---:|---:|---|---|---:|---:|")
        for r in exp1["rows"]:
            pr = r.get("point_read", {})
            or_ = r.get("olap_read", {})
            lines.append(
                f"| {r.get('target_write_rec_s')} | {r.get('writer_threads')} | {r.get('sleep_sec', 0)} | "
                f"{r.get('achieved_write_rec_s')} | {pr.get('p50_ms')}/{pr.get('p95_ms')} | "
                f"{or_.get('p50_ms')}/{or_.get('p95_ms')} | {r.get('active_parts')} | {r.get('compression_ratio')} |"
            )
    exp7 = data.get("exp7_high_write_heavy_olap")
    if exp7:
        lines.append("")
        lines.append("### Exp7 high write + heavy OLAP (parallel)")
        cfg = exp7.get("config", {})
        lat = exp7.get("heavy_olap_latency", {})
        lines.append(
            f"- Duration **{exp7.get('wall_s')}s**, writers **{cfg.get('writer_threads')}**, "
            f"OLAP every **{cfg.get('heavy_olap_interval_sec')}s**"
        )
        lines.append(
            f"- Write **~{exp7.get('write_throughput_rec_s')}** rec/s, **{exp7.get('records_written')}** rows, "
            f"HTTP err batches: **{exp7.get('write_http_error_batches')}**"
        )
        lines.append(
            f"- Heavy OLAP: **{exp7.get('heavy_olap_completed')}** ok, failures **{exp7.get('heavy_olap_failures')}**, "
            f"latency avg/p50/p95: **{lat.get('avg_ms')} / {lat.get('p50_ms')} / {lat.get('p95_ms')}** ms"
        )
    lines.append("")
    jf = (
        "experiments_phase2_exp7_only.json"
        if data.get("mode") == "exp7_only"
        else "experiments_phase2_out.json"
    )
    lines.append(f"### Full JSON: see {jf}")
    return "\n".join(lines)


def main_exp7_only(duration_sec: float = 600.0) -> None:
    preflight()
    print(f"Exp7 only: high write + heavy OLAP (~{duration_sec / 60:.0f} min)…")
    out: dict[str, Any] = {
        "started": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "mode": "exp7_only",
        "exp7_high_write_heavy_olap": exp7_high_write_parallel_heavy_olap(
            duration_sec=duration_sec
        ),
        "finished": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "_recent_write_errors": list(_recent_errors),
    }
    base = "/Users/max/Downloads/ITMO/вкр"
    p_json = base + "/experiments_phase2_exp7_only.json"
    p_md = base + "/EXPERIMENTS_PHASE2_exp7_auto.md"
    with open(p_json, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2, ensure_ascii=False)
    with open(p_md, "w", encoding="utf-8") as f:
        f.write(render_markdown(out))
    print(f"Wrote {p_json} and {p_md}")


def main() -> None:
    preflight()
    print("Phase2 experiments starting…")
    out: dict[str, Any] = {
        "started": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    print("Calibration…")
    cal = calibrate_threads()
    out["calibration"] = cal

    if all(c.get("written", 0) == 0 for c in cal):
        print("FATAL: calibration wrote 0 records. Fix Kafka/API and retry.", file=sys.stderr)
        print("Recent errors:", _recent_errors, file=sys.stderr)
        sys.exit(2)

    print("Exp1 (with throttle tuning, ~10+ min)…")
    out["exp1_read_degradation"] = exp1_read_degradation(cal)

    print("Exp3 (projection)…")
    out["exp3_projection_insert"] = exp3_projection_insert()
    if not (
        out["exp3_projection_insert"].get("with_projection", {}).get("records_ok", 0) > 0
        or out["exp3_projection_insert"].get("without_projection", {}).get("records_ok", 0) > 0
    ):
        print("WARN: Exp3 zero writes", file=sys.stderr)

    print("Exp2 (6+ min)…")
    out["exp2_parts_timeline"] = exp2_parts_timeline()

    print("Exp4…")
    out["exp4_map_vs_mat"] = exp4_map_vs_mat()

    print("Exp5…")
    out["exp5_bloom"] = exp5_bloom()

    print("Exp6…")
    out["exp6_kafka_vs_direct"] = exp6_kafka_vs_direct()

    print("Exp7 (high write + sparse heavy OLAP, ~10 min)…")
    out["exp7_high_write_heavy_olap"] = exp7_high_write_parallel_heavy_olap()

    out["finished"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    out["_recent_write_errors"] = list(_recent_errors)

    base = "/Users/max/Downloads/ITMO/вкр"
    with open(base + "/experiments_phase2_out.json", "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2, ensure_ascii=False)
    with open(base + "/EXPERIMENTS_PHASE2_auto.md", "w", encoding="utf-8") as f:
        f.write(render_markdown(out))
    print("Wrote experiments_phase2_out.json and EXPERIMENTS_PHASE2_auto.md")


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description="EXPERIMENTS_PHASE2 orchestrator")
    ap.add_argument(
        "--exp7-only",
        action="store_true",
        help="Только Exp7 (непрерывная запись + редкие тяжёлые OLAP), без Exp1–6",
    )
    ap.add_argument(
        "--duration",
        type=float,
        default=600.0,
        metavar="SEC",
        help="Длительность Exp7 в секундах (по умолчанию 600)",
    )
    ns = ap.parse_args()
    if ns.exp7_only:
        main_exp7_only(duration_sec=ns.duration)
    else:
        main()
