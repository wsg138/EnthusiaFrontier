#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SMOKE="${RUNNER_TEMP:-/tmp}/frontier-challenges-smoke"
PAPER_NAME='paper-26.3.jar'

rm -rf "$SMOKE"
mkdir -p "$SMOKE/plugins/EnthusiaTempChallenges" "$SMOKE/plugins/EnthusiaTags"

cp "$ROOT/server/plugins/EnthusiaTempChallenges-0.2.0-frontier.1.jar" "$SMOKE/plugins/"
cp "$ROOT/server/plugins/EnthusiaTags.jar" "$SMOKE/plugins/"
cp "$ROOT/server/plugins/EnthusiaTempChallenges/config.yml" "$SMOKE/plugins/EnthusiaTempChallenges/config.yml"
cp "$ROOT/server/plugins/EnthusiaTags/config.yml" "$SMOKE/plugins/EnthusiaTags/config.yml"

user_agent='EnthusiaFrontier challenge validation (github.com/wsg138/EnthusiaFrontier)'
curl --fail --silent --show-error --location --retry 3 \
  -H "User-Agent: ${user_agent}" \
  'https://fill.papermc.io/v3/projects/paper/versions/26.3/builds' \
  -o "$SMOKE/paper-builds.json"
paper_url=''
for channel in STABLE BETA ALPHA; do
  candidate="$(jq -r --arg channel "$channel" '[.[] | select(.channel == $channel)] | sort_by(.id // .number) | last | .downloads."server:default".url // empty' "$SMOKE/paper-builds.json")"
  if [[ -n "$candidate" ]]; then paper_url="$candidate"; break; fi
done
test -n "$paper_url"
curl --fail --silent --show-error --location --retry 3 -H "User-Agent: ${user_agent}" "$paper_url" -o "$SMOKE/$PAPER_NAME"
test -s "$SMOKE/$PAPER_NAME"
sha256sum "$SMOKE/$PAPER_NAME"

cat > "$SMOKE/eula.txt" <<'EOF'
eula=true
EOF
cat > "$SMOKE/server.properties" <<'EOF'
allow-nether=true
difficulty=peaceful
enable-query=false
enable-rcon=false
enforce-secure-profile=false
generate-structures=false
level-name=world
level-type=minecraft:flat
max-players=1
motd=Frontier challenge CI smoke
online-mode=false
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
java -Xms512M -Xmx1536M -jar "$PAPER_NAME" --nogui <"$PIPE" >server.log 2>&1 &
SERVER_PID=$!
popd >/dev/null

cleanup() {
  rc=$?
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    printf 'stop\n' >&3 || true
    for _ in $(seq 1 20); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
    kill "$SERVER_PID" 2>/dev/null || true
  fi
  if (( rc != 0 )); then
    echo '--- Frontier challenge Paper 26.3 smoke failed; server.log follows ---' >&2
    tail -n 300 "$SMOKE/server.log" >&2 || true
    echo '--- end server.log ---' >&2
  fi
  return "$rc"
}
trap cleanup EXIT

started=false
for _ in $(seq 1 180); do
  if grep -qE 'Done \([0-9.]+s\)!|Done \(' "$SMOKE/server.log"; then started=true; break; fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then echo 'Paper exited before reaching Done.' >&2; exit 1; fi
  sleep 1
done
[[ "$started" == true ]] || { echo 'Paper did not reach Done within 180 seconds.' >&2; exit 1; }

printf 'plugins\n' >&3
printf 'tempchallenge status\n' >&3
sleep 4
printf 'stop\n' >&3
for _ in $(seq 1 45); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
if kill -0 "$SERVER_PID" 2>/dev/null; then echo 'Paper did not stop cleanly.' >&2; exit 1; fi
wait "$SERVER_PID"

if grep -qE 'Error occurred while enabling (EnthusiaTags|EnthusiaTempChallenges)|Could not load plugin.*(EnthusiaTags|EnthusiaTempChallenges)|Unknown/missing dependency.*(EnthusiaTags|EnthusiaTempChallenges)' "$SMOKE/server.log"; then
  echo 'One or more TEMP challenge runtime plugins reported an enable/dependency failure.' >&2
  exit 1
fi

grep -q 'EnthusiaTempChallenges enabled: event=frontier_2026_test, state=ACTIVE' "$SMOKE/server.log"
grep -q 'EnthusiaTempChallenges' "$SMOKE/server.log"
grep -q 'EnthusiaTags' "$SMOKE/server.log"
test -s "$SMOKE/plugins/EnthusiaTempChallenges/challenge-ledger.sqlite"

python3 - "$SMOKE/plugins/EnthusiaTempChallenges/challenge-ledger.sqlite" <<'PY'
import sqlite3, sys
with sqlite3.connect(sys.argv[1]) as connection:
    version = connection.execute("SELECT value FROM schema_meta WHERE key='schema_version'").fetchone()
    assert version == ('1',), version
    tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    required = {'challenge_winner', 'attempt_journal', 'reward_delivery', 'dragon_contribution', 'revocation_history'}
    assert not (required - tables), required - tables
    assert connection.execute('SELECT COUNT(*) FROM challenge_winner').fetchone()[0] == 0
PY

trap - EXIT
echo 'Paper 26.3 challenge smoke passed: TEMP challenges and Tags loaded and durable ledger initialized.'
