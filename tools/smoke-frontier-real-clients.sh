#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLUGIN_JAR="${1:?usage: smoke-frontier-real-clients.sh <plugin-jar> <leaf-jar>}"
LEAF_JAR="${2:?usage: smoke-frontier-real-clients.sh <plugin-jar> <leaf-jar>}"
SMOKE="${RUNNER_TEMP:-/tmp}/frontier-real-client-smoke"
SERVER="$SMOKE/server"
SERVER_LOG="$SMOKE/server.log"
PORT=25590
MAX_RATE=8.0
MAX_CONCURRENT=4
MINEFLAYER_VERSION=4.37.1

rm -rf "$SMOKE"
mkdir -p "$SERVER/plugins/EnthusiaFrontier"
cp "$PLUGIN_JAR" "$SERVER/plugins/EnthusiaFrontier-0.1.1.jar"
cp "$LEAF_JAR" "$SERVER/leaf.jar"
cp "$ROOT/src/main/resources/config.yml" "$SERVER/plugins/EnthusiaFrontier/config.yml"
sha256sum "$SERVER/plugins/EnthusiaFrontier-0.1.1.jar" > "$SMOKE/plugin.sha256"
sha256sum "$SERVER/leaf.jar" > "$SMOKE/leaf.sha256"

python3 - "$SERVER/plugins/EnthusiaFrontier/config.yml" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
world = "  world:\n    enabled: true\n    core-radius-blocks: 100000"
if text.count(world) != 1:
    raise SystemExit("could not identify the production overworld core setting")
text = text.replace(world, "  world:\n    enabled: true\n    core-radius-blocks: 4096", 1)
margin = "  extra-guard-radius-chunks: 2"
if text.count(margin) != 1:
    raise SystemExit("could not identify the generation shield guard margin")
# Minimize virgin chunks per client while still exercising the actual runtime
# view/simulation-distance buffer. Production calibration keeps its larger margin.
text = text.replace(margin, "  extra-guard-radius-chunks: 0", 1)
path.write_text(text)
PY

cat > "$SERVER/eula.txt" <<'EOF'
eula=true
EOF
cat > "$SERVER/server.properties" <<EOF
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
max-players=50
motd=Enthusia Frontier real client load test
network-compression-threshold=256
online-mode=false
prevent-proxy-connections=false
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

node -e "const p=require('mineflayer/package.json'); if (p.version !== '$MINEFLAYER_VERSION') { throw new Error('expected mineflayer $MINEFLAYER_VERSION, got ' + p.version); }"

PIPE="$SMOKE/console.pipe"
rm -f "$PIPE"
mkfifo "$PIPE"
exec {CONSOLE_FD}<>"$PIPE"

LAST_PID=''
cleanup() {
  local rc=$?
  if [[ -n "$LAST_PID" ]] && kill -0 "$LAST_PID" 2>/dev/null; then
    printf 'stop\n' >&"$CONSOLE_FD" || true
    for _ in $(seq 1 20); do
      kill -0 "$LAST_PID" 2>/dev/null || break
      sleep 1
    done
    kill "$LAST_PID" 2>/dev/null || true
  fi
  if (( rc != 0 )); then
    echo '--- Frontier real-client smoke failed ---' >&2
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
java -Xms768M -Xmx2048M -jar leaf.jar --nogui <"$PIPE" >"$SERVER_LOG" 2>&1 &
LAST_PID=$!
popd >/dev/null

started=false
for _ in $(seq 1 180); do
  if grep -qE 'Done \([0-9.]+s\)!|Done \(' "$SERVER_LOG"; then
    started=true
    break
  fi
  if ! kill -0 "$LAST_PID" 2>/dev/null; then
    echo 'Leaf exited before reaching Done in real-client smoke.' >&2
    exit 1
  fi
  sleep 1
done
if [[ "$started" != true ]]; then
  echo 'Leaf did not reach Done within 180 seconds in real-client smoke.' >&2
  exit 1
fi

grep -q 'EnthusiaFrontier enabled for 1 world(s); generation-shield=global-paper-async' "$SERVER_LOG"
printf 'gamerule maxEntityCramming 0\n' >&"$CONSOLE_FD"
printf 'frontier status\n' >&"$CONSOLE_FD"
sleep 2

DB="$SERVER/plugins/EnthusiaFrontier/frontier.db"
test -s "$DB"

count_ready() {
  python3 - "$DB" <<'PY'
import sqlite3
import sys
with sqlite3.connect(sys.argv[1]) as connection:
    print(connection.execute(
        'SELECT COUNT(*) FROM frontier_chunk WHERE deleted_at_ms IS NULL'
    ).fetchone()[0])
PY
}

run_case() {
  local count="$1"
  local prefix="$2"
  local direction="$3"
  local start_axis="$4"
  local target_axis="$5"
  local case_dir="$SMOKE/case-$count"
  mkdir -p "$case_dir"
  local before
  before="$(count_ready)"

  node "$ROOT/tools/frontier-real-client-load.js" \
    --host 127.0.0.1 \
    --port "$PORT" \
    --count "$count" \
    --prefix "$prefix" \
    --direction "$direction" \
    --marker-dir "$case_dir" \
    --start-axis "$start_axis" \
    --target-axis "$target_axis" \
    --timeout-ms 240000 \
    >"$case_dir/client.log" 2>&1 &
  local client_pid=$!

  local connected=false
  for _ in $(seq 1 90); do
    if [[ -s "$case_dir/connected.json" ]]; then
      connected=true
      break
    fi
    if ! kill -0 "$client_pid" 2>/dev/null; then
      wait "$client_pid" || true
      echo "Real-client process exited before all $count clients connected." >&2
      return 1
    fi
    sleep 1
  done
  if [[ "$connected" != true ]]; then
    echo "Real-client case $count did not connect within 90 seconds." >&2
    kill "$client_pid" 2>/dev/null || true
    wait "$client_pid" || true
    return 1
  fi

  local index lane username x z
  for ((index = 0; index < count; index++)); do
    lane=$(( (2 * index - (count - 1)) * 48 ))
    username="${prefix}$(printf '%02d' "$index")"
    case "$direction" in
      east|west)
        x="$start_axis"
        z="$lane"
        ;;
      north|south)
        x="$lane"
        z="$start_axis"
        ;;
      *)
        echo "Unsupported direction: $direction" >&2
        return 1
        ;;
    esac
    printf 'execute as %s at @s run teleport @s %s ~ %s\n' "$username" "$x" "$z" >&"$CONSOLE_FD"
  done
  sleep 3

  local start_line
  start_line=$(( $(wc -l < "$SERVER_LOG") + 1 ))
  touch "$case_dir/go"

  (
    while kill -0 "$client_pid" 2>/dev/null; do
      printf 'frontier status\n' >&"$CONSOLE_FD" || exit 0
      sleep 1
    done
  ) &
  local sampler_pid=$!

  set +e
  wait "$client_pid"
  local client_rc=$?
  set -e
  kill "$sampler_pid" 2>/dev/null || true
  wait "$sampler_pid" 2>/dev/null || true
  if (( client_rc != 0 )); then
    echo "Real-client case $count failed." >&2
    return 1
  fi

  printf 'frontier status\n' >&"$CONSOLE_FD"
  sleep 2
  local end_line
  end_line="$(wc -l < "$SERVER_LOG")"
  local after
  after="$(count_ready)"
  local expected=$((15 * count))

  set +e
  python3 - \
    "$case_dir/result.json" "$SERVER_LOG" "$start_line" "$end_line" \
    "$before" "$after" "$expected" "$count" "$MAX_RATE" "$MAX_CONCURRENT" <<'PY' \
    | tee "$case_dir/evidence.txt"
import json
import math
import re
import sys
from pathlib import Path

result_path, log_path, start_line, end_line, before, after, expected, count, rate, max_concurrent = sys.argv[1:]
result = json.loads(Path(result_path).read_text())
if result.get('status') != 'passed':
    raise SystemExit(f"client result was not passed: {result}")
elapsed_ms = float(result['elapsed_ms'])
before = int(before)
after = int(after)
expected = int(expected)
count = int(count)
rate = float(rate)
max_concurrent = int(max_concurrent)
delta = after - before
if delta < expected:
    raise SystemExit(f"case {count}: only {delta} new ready rows, expected at least {expected}")

lines = Path(log_path).read_text(errors='replace').splitlines()[int(start_line) - 1:int(end_line)]
mspt = []
in_flight = []
queued = []
for line in lines:
    if 'MSPT:' in line:
        match = re.search(r'MSPT:.*?([0-9]+(?:\.[0-9]+)?)', line)
        if match:
            mspt.append(float(match.group(1)))
    match = re.search(r'in-flight=.*?([0-9]+)', line)
    if match:
        in_flight.append(int(match.group(1)))
    match = re.search(r'queued=.*?([0-9]+)', line)
    if match:
        queued.append(int(match.group(1)))
if not mspt:
    raise SystemExit(f"case {count}: no MSPT samples were captured")
peak_in_flight = max(in_flight, default=0)
if peak_in_flight > max_concurrent:
    raise SystemExit(
        f"case {count}: observed in-flight {peak_in_flight} above global maximum {max_concurrent}"
    )

# The first global admission may start immediately; every additional generated row
# must consume the server-wide rate budget. Extra rows only make this check stricter,
# which helps catch generation escaping around the movement shield.
minimum_ms = max(0.0, (delta - 1) * 1000.0 / rate)
if elapsed_ms + 2500.0 < minimum_ms:
    raise SystemExit(
        f"case {count}: {delta} managed chunks appeared in {elapsed_ms:.1f}ms, "
        f"faster than the {rate:.3f}/s aggregate ceiling permits ({minimum_ms:.1f}ms minimum)"
    )

def percentile(values, fraction):
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * fraction) - 1))
    return ordered[index]

effective = delta / max(elapsed_ms / 1000.0, 0.001)
print(
    f"FRONTIER_REAL_CLIENT_CASE clients={count} new_ready={delta} elapsed_ms={elapsed_ms:.1f} "
    f"effective_ready_per_second={effective:.3f} peak_queue={max(queued, default=0)} "
    f"peak_in_flight={peak_in_flight} mspt_p50={percentile(mspt, 0.50):.3f} "
    f"mspt_p95={percentile(mspt, 0.95):.3f} mspt_p99={percentile(mspt, 0.99):.3f} "
    f"mspt_max={max(mspt):.3f}"
)
PY
  local python_rc=${PIPESTATUS[0]}
  set -e
  if (( python_rc != 0 )); then
    return "$python_rc"
  fi
}

# Each direction starts inside the permanent square and finishes in a distinct virgin
# frontier edge. Lanes are six chunks apart while the test runtime guard radius is two,
# so different clients cannot satisfy one another's safety buffers.
run_case 1  FrE east   4040  4120
run_case 10 FrW west  -4024 -4104
run_case 20 FrS south  4040  4120
run_case 40 FrN north -4024 -4104

printf 'frontier status\n' >&"$CONSOLE_FD"
printf 'stop\n' >&"$CONSOLE_FD"
for _ in $(seq 1 60); do
  kill -0 "$LAST_PID" 2>/dev/null || break
  sleep 1
done
if kill -0 "$LAST_PID" 2>/dev/null; then
  echo 'Leaf did not stop cleanly after real-client load testing.' >&2
  exit 1
fi
wait "$LAST_PID"
LAST_PID=''

python3 - "$DB" <<'PY'
import sqlite3
import sys
with sqlite3.connect(sys.argv[1]) as connection:
    assert connection.execute('PRAGMA integrity_check').fetchone() == ('ok',)
    ready = connection.execute(
        'SELECT COUNT(*) FROM frontier_chunk WHERE deleted_at_ms IS NULL'
    ).fetchone()[0]
    assert ready >= 15 * (1 + 10 + 20 + 40), ready
PY

test ! -e "$SERVER/plugins/EnthusiaFrontier/CLEANUP_UNSAFE.latch"
if grep -qE 'EnthusiaFrontier failed safe during startup|Global generation shield failed closed|FRONTIER_REAL_CLIENT_FAILED' "$SERVER_LOG"; then
  echo 'Frontier reported a fail-closed/runtime failure during real-client testing.' >&2
  exit 1
fi

trap - EXIT
echo 'FRONTIER_REAL_CLIENT_LOAD_OK clients=1,10,20,40 real_network_clients=true'
