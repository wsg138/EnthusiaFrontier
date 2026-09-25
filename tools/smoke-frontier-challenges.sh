#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SMOKE="${RUNNER_TEMP:-/tmp}/frontier-challenges-smoke"
LEAF_NAME='leaf-1.21.11-179.jar'
LEAF_URL='https://github.com/Winds-Studio/Leaf/releases/download/ver-1.21.11/leaf-1.21.11-179.jar'
LEAF_SHA='5da79782215c1a25edcd7c73b3523b7ecb7f4b86dc8a5846a176ed69bc2cd020'

rm -rf "$SMOKE"
mkdir -p "$SMOKE/plugins/EnthusiaTempChallenges" \
         "$SMOKE/plugins/EnthusiaAdvancements/trees" \
         "$SMOKE/plugins/EnthusiaTags"

cp "$ROOT/server/plugins/EnthusiaTempChallenges-0.2.0-frontier.1.jar" "$SMOKE/plugins/"
cp "$ROOT/server/plugins/EnthusiaAdvancements-1.0.0-frontier.jar" "$SMOKE/plugins/"
cp "$ROOT/server/plugins/UltimateAdvancementAPI-2.8.1.jar" "$SMOKE/plugins/"
cp "$ROOT/server/plugins/EnthusiaTags.jar" "$SMOKE/plugins/"
cp "$ROOT/server/plugins/EnthusiaTempChallenges/config.yml" "$SMOKE/plugins/EnthusiaTempChallenges/config.yml"
cp "$ROOT/server/plugins/EnthusiaAdvancements/trees/frontier_firsts.conf" "$SMOKE/plugins/EnthusiaAdvancements/trees/frontier_firsts.conf"
cp "$ROOT/server/plugins/EnthusiaTags/config.yml" "$SMOKE/plugins/EnthusiaTags/config.yml"

curl --fail --location --retry 3 --silent --show-error "$LEAF_URL" -o "$SMOKE/$LEAF_NAME"
echo "$LEAF_SHA  $SMOKE/$LEAF_NAME" | sha256sum --check --status

cat > "$SMOKE/eula.txt" <<'EOF'
eula=true
EOF
cat > "$SMOKE/server.properties" <<'EOF'
accepts-transfers=false
allow-flight=false
allow-nether=true
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
max-players=1
motd=Frontier challenge CI smoke
network-compression-threshold=256
online-mode=false
prevent-proxy-connections=false
query.port=25587
server-ip=127.0.0.1
server-port=25587
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

PIPE="$SMOKE/console.pipe"
mkfifo "$PIPE"
exec 3<>"$PIPE"

pushd "$SMOKE" >/dev/null
java -Xms512M -Xmx1536M -jar "$LEAF_NAME" --nogui <"$PIPE" >server.log 2>&1 &
SERVER_PID=$!
popd >/dev/null

cleanup() {
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    printf 'stop\n' >&3 || true
    for _ in $(seq 1 20); do
      kill -0 "$SERVER_PID" 2>/dev/null || return 0
      sleep 1
    done
    kill "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

started=false
for _ in $(seq 1 180); do
  if grep -qE 'Done \([0-9.]+s\)!|Done \(' "$SMOKE/server.log"; then
    started=true
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo 'Leaf exited before reaching Done.' >&2
    tail -n 250 "$SMOKE/server.log" >&2
    exit 1
  fi
  sleep 1
done

if [[ "$started" != true ]]; then
  echo 'Leaf did not reach Done within 180 seconds.' >&2
  tail -n 250 "$SMOKE/server.log" >&2
  exit 1
fi

printf 'plugins\n' >&3
printf 'tempchallenge status\n' >&3
sleep 4
printf 'stop\n' >&3

for _ in $(seq 1 45); do
  kill -0 "$SERVER_PID" 2>/dev/null || break
  sleep 1
done
if kill -0 "$SERVER_PID" 2>/dev/null; then
  echo 'Leaf did not stop cleanly.' >&2
  tail -n 250 "$SMOKE/server.log" >&2
  exit 1
fi
wait "$SERVER_PID"
trap - EXIT

if grep -q 'Could not enable durable Frontier Firsts' "$SMOKE/server.log"; then
  echo 'EnthusiaTempChallenges reported an enable failure.' >&2
  tail -n 250 "$SMOKE/server.log" >&2
  exit 1
fi

grep -q 'EnthusiaTempChallenges enabled: event=frontier_2026_test, state=ACTIVE' "$SMOKE/server.log"
grep -q 'Frontier Firsts' "$SMOKE/server.log"
grep -q 'EnthusiaTempChallenges' "$SMOKE/server.log"
grep -q 'EnthusiaAdvancements' "$SMOKE/server.log"
grep -q 'EnthusiaTags' "$SMOKE/server.log"
test -s "$SMOKE/plugins/EnthusiaTempChallenges/challenge-ledger.sqlite"

python3 - "$SMOKE/plugins/EnthusiaTempChallenges/challenge-ledger.sqlite" <<'PY'
import sqlite3
import sys

path = sys.argv[1]
with sqlite3.connect(path) as connection:
    version = connection.execute("SELECT value FROM schema_meta WHERE key='schema_version'").fetchone()
    assert version == ('1',), version
    tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    required = {'challenge_winner', 'attempt_journal', 'reward_delivery', 'dragon_contribution', 'revocation_history'}
    missing = required - tables
    assert not missing, missing
    assert connection.execute('SELECT COUNT(*) FROM challenge_winner').fetchone()[0] == 0
PY

echo 'Leaf 1.21.11 runtime smoke passed: challenge plugin enabled, status command ran, and empty durable ledger schema initialized.'
