#!/usr/bin/env bash
# Runs the three recorded HTTP load profiles against an already-running platform
# (start it with: java -XX:+UseZGC -XX:+ZGenerational -Xms1g -Xmx1g -jar platform-app/target/platform-app.jar).
# Results land in eval/results/. Restart the platform between profiles for comparable numbers.
set -euo pipefail
cd "$(dirname "$0")/.."
URL="${URL:-http://localhost:8080}"
JAR=load-test/target/load-test.jar
[ -f "$JAR" ] || ./mvnw -q -pl load-test -am package -DskipTests -DskipUi
run() { java -XX:+UseZGC -XX:+ZGenerational -Xmx512m -jar "$JAR" --url "$URL" --connections 64 --duration 60 "$@"; }
case "${1:-all}" in
  max)  run --out eval/results/http-max-throughput.json ;;
  20k)  run --rate 20000 --out eval/results/http-target-20k.json ;;
  10k)  run --rate 10000 --out eval/results/http-sustained-10k.json ;;
  all)  run --out eval/results/http-max-throughput.json
        run --rate 20000 --out eval/results/http-target-20k.json
        run --rate 10000 --out eval/results/http-sustained-10k.json ;;
  *)    echo "usage: $0 [max|20k|10k|all]"; exit 2 ;;
esac
