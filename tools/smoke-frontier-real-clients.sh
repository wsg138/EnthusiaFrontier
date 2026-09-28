#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLUGIN_JAR="${1:?usage: smoke-frontier-real-clients.sh <plugin-jar> <paper-jar>}"
PAPER_JAR="${2:?usage: smoke-frontier-real-clients.sh <plugin-jar> <paper-jar>}"
SMOKE="${RUNNER_TEMP:-/tmp}/frontier-real-client-smoke"
SERVER="$SMOKE/server"
SERVER_LOG="$SMOKE/server.log"
PORT=25590
MINEFLAYER_VERSION=4.42.2
CLIENT_TIMEOUT_MS=600000
WALK_TIMEOUT_MS=240000
CLIENT_RETRY_INTERVAL_SECONDS=5

rm -rf "$SMOKE"
mkdir -p "$SERVER/plugins/EnthusiaFrontier"
cp "$PLUGIN_JAR" "$SERVER/plugins/EnthusiaFrontier-0.1.1.jar"
cp "$PAPER_JAR" "$SERVER/paper.jar"
cp "$ROOT/src/main/resources/config.yml" "$SERVER/plugins/EnthusiaFrontier/config.yml"
sha256sum "$SERVER/plugins/EnthusiaFrontier-0.1.1.jar" > "$SMOKE/plugin.sha256"
sha256sum "$SERVER/paper.jar" > "$SMOKE/paper.sha256"

python3 - "$SERVER/plugins/EnthusiaFrontier/config.yml" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
text = path.read_text()
needle = "  world:\n    enabled: true\n    core-radius-blocks: 100000"
if text.count(needle) != 1:
    raise SystemExit('could not identify the production overworld core setting')
path.write_text(text.replace(needle, "  world:\n    enabled: true\n    core-radius-blocks: 8192", 1))
PY

cat > "$SERVER/eula.txt" <<'EOF'
eula=true
EOF
cat > "$SERVER/server.properties" <<EOF
allow-flight=false
allow-nether=false
difficulty=peaceful
enforce-secure-profile=false
generate-structures=false
level-name=world
level-type=minecraft:flat
max-players=50
motd=Enthusia Frontier real client load test
online-mode=false
server-ip=127.0.0.1
server-port=$PORT
simulation-distance=2
spawn-animals=false
spawn-monsters=false
spawn-npcs=false
spawn-protection=0
sync-chunk-writes=false
use-native-transport=false
view-distance=2
white-list=false
EOF

node -e "const p=require('mineflayer/package.json'); if (p.version !== '$MINEFLAYER_VERSION') throw new Error('expected mineflayer $MINEFLAYER_VERSION, got ' + p.version);"

PIPE="$SMOKE/console.pipe"
mkfifo "$PIPE"
exec {CONSOLE_FD}<>"$PIPE"
LAST_PID=''

cleanup() {
  local rc=$?
  if [[ -n "$LAST_PID" ]] && kill -0 "$LAST_PID" 2>/dev/null; then
    printf 'stop\n' >&"$CONSOLE_FD" || true
    for _ in $(seq 1 20); do kill -0 "$LAST_PID" 2>/dev/null || break; sleep 1; done
    kill "$LAST_PID" 2>/dev/null || true
  fi
  if (( rc != 0 )); then
    echo '--- Frontier Paper 26.3 real-client smoke failed ---' >&2
    tail -n 500 "$SERVER_LOG" >&2 || true
    for log in "$SMOKE"/case-*/client.log; do
      [[ -f "$log" ]] || continue
      echo "--- ${log#$SMOKE/} ---" >&2
      cat "$log" >&2 || true
    done
  fi
  return "$rc"
}
trap cleanup EXIT

pushd "$SERVER" >/dev/null
java -Xms768M -Xmx2048M -jar paper.jar --nogui <"$PIPE" >"$SERVER_LOG" 2>&1 &
LAST_PID=$!
popd >/dev/null

started=false
for _ in $(seq 1 180); do
  if grep -qE 'Done \([0-9.]+s\)!|Done \(' "$SERVER_LOG"; then started=true; break; fi
  if ! kill -0 "$LAST_PID" 2>/dev/null; then echo 'Paper exited before reaching Done.' >&2; exit 1; fi
  sleep 1
done
[[ "$started" == true ]] || { echo 'Paper did not reach Done within 180 seconds.' >&2; exit 1; }

grep -q 'EnthusiaFrontier enabled for 1 world(s); generation-shield=global-paper-async' "$SERVER_LOG"
printf 'frontier status\n' >&"$CONSOLE_FD"
sleep 2
DB="$SERVER/plugins/EnthusiaFrontier/frontier.db"
test -s "$DB"

teleport_case_players() {
  local count="$1" prefix="$2" direction="$3" axis="$4"
  for ((index=0; index<count; index++)); do
    lane=$(( (2 * index - (count - 1)) * 192 ))
    username="${prefix}$(printf '%02d' "$index")"
    case "$direction" in
      east|west) x="$axis"; z="$lane" ;;
      north|south) x="$lane"; z="$axis" ;;
      *) echo "Unsupported direction: $direction" >&2; return 1 ;;
    esac
    printf 'execute as %s at @s run teleport @s %s ~ %s\n' "$username" "$x" "$z" >&"$CONSOLE_FD"
  done
}

wait_for_connected_and_positioned() {
  local client_pid="$1" case_dir="$2" count="$3" prefix="$4" direction="$5" start_axis="$6"
  for _ in $(seq 1 90); do
    [[ -s "$case_dir/connected.json" ]] && break
    kill -0 "$client_pid" 2>/dev/null || { wait "$client_pid" || true; echo "Clients exited before $case_dir connected." >&2; return 1; }
    sleep 1
  done
  [[ -s "$case_dir/connected.json" ]] || { kill "$client_pid" 2>/dev/null || true; return 1; }

  for _ in $(seq 1 90); do
    [[ -s "$case_dir/positioned.json" ]] && break
    kill -0 "$client_pid" 2>/dev/null || { wait "$client_pid" || true; echo "Clients exited before $case_dir was positioned." >&2; return 1; }
    teleport_case_players "$count" "$prefix" "$direction" "$start_axis"
    sleep 1
  done
  [[ -s "$case_dir/positioned.json" ]] || { kill "$client_pid" 2>/dev/null || true; return 1; }
}

run_walk_case() {
  local case_dir="$SMOKE/case-walk-1"
  local count=1 prefix='FrWalk' direction='west' start_axis=-8008 target_axis=-8216
  local drive_attempts=$(( (WALK_TIMEOUT_MS + CLIENT_RETRY_INTERVAL_SECONDS * 1000 - 1) / (CLIENT_RETRY_INTERVAL_SECONDS * 1000) + 1 ))
  mkdir -p "$case_dir"

  node "$ROOT/tools/frontier-real-client-load.js" \
    --host 127.0.0.1 --port "$PORT" --count "$count" --prefix "$prefix" \
    --direction "$direction" --marker-dir "$case_dir" \
    --start-axis "$start_axis" --target-axis "$target_axis" \
    --timeout-ms "$WALK_TIMEOUT_MS" --movement-mode client-walk \
    >"$case_dir/client.log" 2>&1 &
  local client_pid=$!

  wait_for_connected_and_positioned "$client_pid" "$case_dir" "$count" "$prefix" "$direction" "$start_axis"
  touch "$case_dir/go"
  for _ in $(seq 1 "$drive_attempts"); do
    [[ -s "$case_dir/result.json" ]] && break
    kill -0 "$client_pid" 2>/dev/null || break
    printf 'frontier status\n' >&"$CONSOLE_FD"
    sleep "$CLIENT_RETRY_INTERVAL_SECONDS"
  done

  set +e
  wait "$client_pid"
  client_rc=$?
  set -e
  if (( client_rc != 0 )); then
    echo 'Real walking-client boundary case failed.' >&2
    return "$client_rc"
  fi

  python3 - "$case_dir/result.json" <<'PY'
import json, sys
result=json.load(open(sys.argv[1]))
assert result.get('status') == 'passed', result
assert result.get('count') == 1, result
assert len(result.get('positions', [])) == 1, result
assert result.get('movement_mode') == 'client-walk', result
print(f"FRONTIER_REAL_WALK_CASE elapsed_ms={result['elapsed_ms']:.1f}")
PY
  printf 'frontier status\n' >&"$CONSOLE_FD"
  sleep 1
}

run_case() {
  local count="$1" prefix="$2" direction="$3" start_axis="$4" target_axis="$5"
  local case_dir="$SMOKE/case-$count"
  local drive_attempts=$(( (CLIENT_TIMEOUT_MS + CLIENT_RETRY_INTERVAL_SECONDS * 1000 - 1) / (CLIENT_RETRY_INTERVAL_SECONDS * 1000) + 1 ))
  mkdir -p "$case_dir"

  node "$ROOT/tools/frontier-real-client-load.js" \
    --host 127.0.0.1 --port "$PORT" --count "$count" --prefix "$prefix" \
    --direction "$direction" --marker-dir "$case_dir" \
    --start-axis "$start_axis" --target-axis "$target_axis" \
    --timeout-ms "$CLIENT_TIMEOUT_MS" --movement-mode server-teleport-with-real-network-clients \
    >"$case_dir/client.log" 2>&1 &
  local client_pid=$!

  wait_for_connected_and_positioned "$client_pid" "$case_dir" "$count" "$prefix" "$direction" "$start_axis"

  # Keep real 26.3 network clients connected while Frontier guards repeated
  # server-side teleport requests at the generation boundary. The walking case above
  # covers ordinary client movement; these cases exercise aggregate 1/10/20/40 load.
  touch "$case_dir/go"
  for _ in $(seq 1 "$drive_attempts"); do
    [[ -s "$case_dir/result.json" ]] && break
    kill -0 "$client_pid" 2>/dev/null || break
    teleport_case_players "$count" "$prefix" "$direction" "$target_axis"
    printf 'frontier status\n' >&"$CONSOLE_FD"
    sleep "$CLIENT_RETRY_INTERVAL_SECONDS"
  done

  set +e
  wait "$client_pid"
  client_rc=$?
  set -e
  if (( client_rc != 0 )); then
    echo "Real-client case $count failed." >&2
    return "$client_rc"
  fi

  python3 - "$case_dir/result.json" "$count" <<'PY'
import json, sys
result=json.load(open(sys.argv[1]))
expected=int(sys.argv[2])
assert result.get('status') == 'passed', result
assert result.get('count') == expected, result
assert len(result.get('positions', [])) == expected, result
assert result.get('movement_mode') == 'server-teleport-with-real-network-clients', result
print(f"FRONTIER_REAL_CLIENT_CASE clients={expected} elapsed_ms={result['elapsed_ms']:.1f}")
PY
  printf 'frontier status\n' >&"$CONSOLE_FD"
  sleep 1
}

run_walk_case
run_case 1  FrE east   8008  8216
run_case 10 FrW west  -7992 -8200
run_case 20 FrS south  8008  8216
run_case 40 FrN north -7992 -8200

printf 'frontier status\n' >&"$CONSOLE_FD"
printf 'stop\n' >&"$CONSOLE_FD"
for _ in $(seq 1 60); do kill -0 "$LAST_PID" 2>/dev/null || break; sleep 1; done
if kill -0 "$LAST_PID" 2>/dev/null; then echo 'Paper did not stop cleanly after real-client load testing.' >&2; exit 1; fi
wait "$LAST_PID"
LAST_PID=''

python3 - "$DB" <<'PY'
import sqlite3, sys
with sqlite3.connect(sys.argv[1]) as connection:
    assert connection.execute('PRAGMA integrity_check').fetchone() == ('ok',)
    ready = connection.execute('SELECT COUNT(*) FROM frontier_chunk WHERE deleted_at_ms IS NULL').fetchone()[0]
    assert ready > 0, ready
    print(f'FRONTIER_DB_READY_ROWS={ready}')
PY

test ! -e "$SERVER/plugins/EnthusiaFrontier/CLEANUP_UNSAFE.latch"
if grep -qE 'EnthusiaFrontier failed safe during startup|Global generation shield failed closed' "$SERVER_LOG"; then
  echo 'Frontier reported a fail-closed/runtime failure during real-client testing.' >&2
  exit 1
fi

trap - EXIT
echo 'FRONTIER_REAL_CLIENT_LOAD_OK walk=1 clients=1,10,20,40 minecraft=26.3 real_network_clients=true'
