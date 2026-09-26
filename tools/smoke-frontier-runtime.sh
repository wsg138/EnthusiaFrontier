#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLUGIN_JAR="${1:?usage: smoke-frontier-runtime.sh <plugin-jar> <leaf-jar>}"
LEAF_JAR="${2:?usage: smoke-frontier-runtime.sh <plugin-jar> <leaf-jar>}"
SMOKE="${RUNNER_TEMP:-/tmp}/frontier-runtime-smoke"

rm -rf "$SMOKE"
mkdir -p "$SMOKE/server/plugins/EnthusiaFrontier"
cp "$PLUGIN_JAR" "$SMOKE/server/plugins/EnthusiaFrontier-0.1.1.jar"
cp "$ROOT/src/main/resources/config.yml" "$SMOKE/server/plugins/EnthusiaFrontier/config.yml"
cp "$LEAF_JAR" "$SMOKE/server/leaf.jar"
sha256sum "$SMOKE/server/plugins/EnthusiaFrontier-0.1.1.jar" > "$SMOKE/plugin.sha256"
sha256sum "$SMOKE/server/leaf.jar" > "$SMOKE/leaf.sha256"

cat > "$SMOKE/server/eula.txt" <<'EOF'
eula=true
EOF
cat > "$SMOKE/server/server.properties" <<'EOF'
accepts-transfers=false
allow-flight=false
allow-nether=false
difficulty=peaceful
enable-command-block=false
enable-query=false
enable-rcon=false
enforce-secure-profile=false
enforce-whitelist=false
generate-structures=false
hardcore=false
level-name=world
level-type=minecraft:flat
max-players=2
motd=Enthusia Sentinel isolated smoke test
network-compression-threshold=256
online-mode=false
prevent-proxy-connections=false
server-ip=127.0.0.1
server-port=25589
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

LAST_PID=''
LAST_FD=''

stop_if_running() {
  if [[ -n "$LAST_PID" ]] && kill -0 "$LAST_PID" 2>/dev/null; then
    if [[ -n "$LAST_FD" ]]; then
      printf 'stop\n' >&"$LAST_FD" || true
    fi
    for _ in $(seq 1 20); do
      kill -0 "$LAST_PID" 2>/dev/null || break
      sleep 1
    done
    kill "$LAST_PID" 2>/dev/null || true
  fi
}

cleanup() {
  rc=$?
  stop_if_running
  if (( rc != 0 )); then
    echo '--- EnthusiaFrontier Leaf runtime smoke failed ---' >&2
    for log in "$SMOKE"/cycle-*.log; do
      [[ -f "$log" ]] || continue
      echo "--- ${log##*/} ---" >&2
      tail -n 300 "$log" >&2 || true
    done
  fi
  return "$rc"
}
trap cleanup EXIT

run_cycle() {
  local cycle="$1"
  local pipe="$SMOKE/console-$cycle.pipe"
  local log="$SMOKE/cycle-$cycle.log"
  rm -f "$pipe"
  mkfifo "$pipe"

  # Open the FIFO read/write so the Java process can start before the first command.
  exec {console_fd}<>"$pipe"
  LAST_FD="$console_fd"

  pushd "$SMOKE/server" >/dev/null
  java -Xms512M -Xmx1536M -jar leaf.jar --nogui <"$pipe" >"$log" 2>&1 &
  local pid=$!
  popd >/dev/null
  LAST_PID="$pid"

  local started=false
  for _ in $(seq 1 180); do
    if grep -qE 'Done \([0-9.]+s\)!|Done \(' "$log"; then
      started=true
      break
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "Leaf exited before reaching Done on cycle $cycle." >&2
      return 1
    fi
    sleep 1
  done
  if [[ "$started" != true ]]; then
    echo "Leaf did not reach Done within 180 seconds on cycle $cycle." >&2
    return 1
  fi

  printf 'frontier status\n' >&"$console_fd"
  sleep 3
  printf 'stop\n' >&"$console_fd"

  for _ in $(seq 1 45); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "Leaf did not stop cleanly on cycle $cycle." >&2
    return 1
  fi
  wait "$pid"
  exec {console_fd}>&-
  LAST_PID=''
  LAST_FD=''

  if grep -qE 'EnthusiaFrontier failed safe during startup|Error occurred while enabling EnthusiaFrontier|Could not load plugin.*EnthusiaFrontier|Exception.*EnthusiaFrontier' "$log"; then
    echo "EnthusiaFrontier reported a startup/runtime failure on cycle $cycle." >&2
    return 1
  fi
  grep -q 'EnthusiaFrontier enabled for 1 world(s); generation-shield=global-paper-async' "$log"
  grep -q 'global shield:' "$log"
  grep -q 'safety latch:' "$log"
}

run_cycle 1

DB="$SMOKE/server/plugins/EnthusiaFrontier/frontier.db"
test -s "$DB"
python3 - "$DB" <<'PY'
import sqlite3
import sys

path = sys.argv[1]
with sqlite3.connect(path) as connection:
    integrity = connection.execute('PRAGMA integrity_check').fetchone()
    assert integrity == ('ok',), integrity
    tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert 'frontier_chunk' in tables, tables
PY

test ! -e "$SMOKE/server/plugins/EnthusiaFrontier/CLEANUP_UNSAFE.latch"

# Restart against the exact same state directory. This catches shutdown ordering,
# WAL/SQLite reopen problems, readiness-ledger close bugs, and stale safety latches.
run_cycle 2

python3 - "$DB" <<'PY'
import sqlite3
import sys

with sqlite3.connect(sys.argv[1]) as connection:
    integrity = connection.execute('PRAGMA integrity_check').fetchone()
    assert integrity == ('ok',), integrity
PY

test ! -e "$SMOKE/server/plugins/EnthusiaFrontier/CLEANUP_UNSAFE.latch"

trap - EXIT
echo 'FRONTIER_REAL_LEAF_RUNTIME_OK'
echo 'Leaf 1.21.11 runtime smoke passed: exact Frontier JAR enabled, status ran, SQLite stayed healthy, shutdown was clean, and the same state reopened after restart.'
