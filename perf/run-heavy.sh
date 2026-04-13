#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:8082}"
python3 perf/stress_runner.py --profile heavy --base-url "$BASE_URL"
