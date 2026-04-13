# EXPERIMENTS PHASE 2 (auto)

_Generated: 2026-04-03T17:36:49Z_

## Exp1 throttle plan

```json
[
  {
    "target": 5000,
    "threads": 2,
    "sleep_sec": 0.5967,
    "calibration_achieved_rec_s": 5192.0
  },
  {
    "target": 10000,
    "threads": 4,
    "sleep_sec": 0.5637,
    "calibration_achieved_rec_s": 10044.0
  },
  {
    "target": 15000,
    "threads": 4,
    "sleep_sec": 0.0635,
    "calibration_achieved_rec_s": 14966.0
  },
  {
    "target": 20000,
    "threads": 4,
    "sleep_sec": 0.0025,
    "calibration_achieved_rec_s": 16101.0
  }
]
```

## Tables

### Exp1 read degradation
| Target | Thr | sleep | Achieved rec/s | Point p50/p95 | OLAP p50/p95 | Parts | Ratio |
|---:|---:|---:|---:|---|---|---:|---:|
| 0 | 0 | 0.0 | 0 | 61.48/151.63 | 443.59/1159.53 | 28 | 7.905 |
| 5000 | 2 | 0.5967 | 5400.0 | 38.22/62.65 | 610.52/1140.68 | 33 | 7.744 |
| 10000 | 4 | 0.5637 | 11400.0 | 65.9/178.88 | 1167.55/2291.53 | 38 | 7.548 |
| 15000 | 4 | 0.0635 | 15200.0 | 206.91/441.0 | 3648.95/6079.93 | 32 | 6.978 |
| 20000 | 4 | 0.0025 | 16000.0 | 182.54/273.11 | 5058.89/7100.33 | 40 | 6.845 |

### Full JSON: see experiments_phase2_out.json