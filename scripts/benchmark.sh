#!/usr/bin/env bash
# Quick throughput benchmark using hey (https://github.com/rakyll/hey).
# Falls back to ab if hey is missing.
#
# Usage:
#   ./scripts/benchmark.sh              # hits http://localhost:8080
#   TARGET=http://localhost:8081 ./scripts/benchmark.sh
#
# Each request uses a unique client id so we measure raw throughput,
# not the limiter actively rejecting traffic. Swap -H if you want to
# stress-test the rejection path instead.

set -euo pipefail
TARGET="${TARGET:-http://localhost:8080}/api/limited?policy=token_bucket"
N="${N:-20000}"
C="${C:-200}"

echo "Benchmarking $TARGET  (n=$N, c=$C)"
if command -v hey >/dev/null 2>&1; then
  hey -n "$N" -c "$C" -H "X-Client-Id: bench-$(date +%s%N)" "$TARGET"
elif command -v ab >/dev/null 2>&1; then
  ab -n "$N" -c "$C" -H "X-Client-Id: bench-$(date +%s%N)" "$TARGET"
else
  echo "Install 'hey' or apache-bench (ab) to run the benchmark."
  exit 1
fi
