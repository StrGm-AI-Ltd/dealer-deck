#!/usr/bin/env bash
# One command for the v0.5 demo: build, start both DECK servers (stub + Nebius), DEALER and the DEALER UI,
# run the scenario, then keep everything running for questions. Ctrl-C stops it all.
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${NEBIUS_API_KEY:-}" ]; then
  echo "Set NEBIUS_API_KEY first. DEALER holds it and forwards it to the Nebius DECK server (D104). Never commit it." >&2
  exit 1
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if ! "$JAVA" -version 2>&1 | grep -q -E 'version "(2[1-9]|[3-9][0-9])'; then
  echo "Java 21 or newer is required (set JAVA_HOME)." >&2
  exit 1
fi

mkdir -p demo-logs
: > demo-logs/pids
cleanup() { kill $(cat demo-logs/pids) 2>/dev/null || true; pkill -f DealerUiApplication 2>/dev/null || true; }
trap cleanup EXIT INT TERM
start() { local name=$1; shift; "$@" > "demo-logs/$name.log" 2>&1 & echo $! >> demo-logs/pids; }
wait_for() { for _ in $(seq 1 120); do curl -sf -o /dev/null "$1" && return 0; sleep 1; done; echo "Timed out waiting for $1" >&2; exit 1; }

echo "Building..."
./gradlew -q :deck:bootJar :dealer:bootJar
start deck-stub "$JAVA" -jar deck/build/libs/deck.jar --spring.profiles.active=stub --deck.time-factor="${TIME_FACTOR:-0.5}"
start deck-nebius "$JAVA" -jar deck/build/libs/deck.jar --spring.profiles.active=external
start dealer "$JAVA" -jar dealer/build/libs/dealer.jar
start dealer-ui ./gradlew -q :dealer-ui:bootRun
wait_for http://localhost:8081/actuator/health
wait_for http://localhost:8082/actuator/health
wait_for http://localhost:8080/actuator/health
wait_for http://localhost:8090/

cat <<'INFO'

  DEALER UI ............ http://localhost:8090
  DEALER Swagger UI .... http://localhost:8080/swagger-ui.html
  DECK stub Swagger .... http://localhost:8081/swagger-ui.html
  DECK Nebius Swagger .. http://localhost:8082/swagger-ui.html
  Logs ................. demo-logs/

INFO
if [ -z "${DEMO_NO_PROMPT:-}" ]; then
  read -r -p "Open the DEALER UI, then press Enter to start the scenario... "
fi
./scripts/scenario.sh
echo
echo "The services keep running for questions. Press Ctrl-C to stop."
wait
