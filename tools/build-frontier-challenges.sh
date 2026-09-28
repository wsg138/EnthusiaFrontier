#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/server/plugins"
WORK="${RUNNER_TEMP:-/tmp}/frontier-challenges-build"
TAGS_SOURCE='261efb9144216ae86a4a2c8ed406a7f44dcae851'
PAPER_API='26.3.build.49-alpha'

rm -rf "$WORK"
mkdir -p "$WORK" "$OUT" "$OUT/EnthusiaTempChallenges" "$OUT/EnthusiaTags"

normalize_jar() {
  local jar_path="$1"
  python3 - "$jar_path" <<'PY'
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo
import os, sys
path=Path(sys.argv[1]); tmp=path.with_suffix(path.suffix+'.normalized'); fixed=(1980,1,1,0,0,0)
with ZipFile(path,'r') as source, ZipFile(tmp,'w',allowZip64=True) as target:
    for info in sorted(source.infolist(), key=lambda i:(i.filename,i.header_offset)):
        data=source.read(info); out=ZipInfo(info.filename,fixed)
        out.create_system=info.create_system; out.create_version=info.create_version; out.extract_version=info.extract_version
        out.external_attr=info.external_attr; out.internal_attr=info.internal_attr; out.comment=info.comment; out.extra=b''
        if info.is_dir(): out.compress_type=ZIP_STORED; target.writestr(out,b'')
        else: out.compress_type=ZIP_DEFLATED; target.writestr(out,data,compress_type=ZIP_DEFLATED,compresslevel=9)
os.replace(tmp,path)
PY
}

echo '== EnthusiaTempChallenges: Java 25 / Paper 26.3 tests + package =='
mvn -B --no-transfer-progress -f "$ROOT/server-src/EnthusiaTempChallenges/pom.xml" clean verify
cp "$ROOT/server-src/EnthusiaTempChallenges/target/EnthusiaTempChallenges-0.2.0-frontier.1.jar" "$OUT/EnthusiaTempChallenges-0.2.0-frontier.1.jar"
cp "$ROOT/server-src/EnthusiaTempChallenges/src/main/resources/config.yml" "$OUT/EnthusiaTempChallenges/config.yml"
normalize_jar "$OUT/EnthusiaTempChallenges-0.2.0-frontier.1.jar"

echo '== EnthusiaTags: pinned source rebuilt for Java 25 / Paper 26.3 =='
git clone --quiet https://github.com/wsg138/EnthusiaTags.git "$WORK/tags"
git -C "$WORK/tags" checkout --quiet "$TAGS_SOURCE"
python3 - "$WORK/tags" "$PAPER_API" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1]); paper=sys.argv[2]
pom=root/'pom.xml'; text=pom.read_text()
text=text.replace('<maven.compiler.source>21</maven.compiler.source>', '<maven.compiler.source>25</maven.compiler.source>')
text=text.replace('<maven.compiler.target>21</maven.compiler.target>', '<maven.compiler.target>25</maven.compiler.target>')
text=text.replace('<version>1.21.11-R0.1-SNAPSHOT</version>', f'<version>{paper}</version>', 1)
pom.write_text(text)
plugin=root/'src/main/resources/plugin.yml'; yml=plugin.read_text()
yml=yml.replace('api-version: 1.21', "api-version: '26.2'", 1)
plugin.write_text(yml)
PY
( cd "$WORK/tags" && bash tools/bootstrap_loreitems_release.sh && mvn -B --no-transfer-progress clean package )
TAG_JAR="$(find "$WORK/tags/target" -maxdepth 1 -type f -name '*.jar' ! -name 'original-*' -printf '%s %p\n' | sort -nr | head -1 | cut -d' ' -f2-)"
test -n "$TAG_JAR"
cp "$TAG_JAR" "$OUT/EnthusiaTags.jar"
normalize_jar "$OUT/EnthusiaTags.jar"

# Keep the curated Frontier-first tag config already tracked in this runtime.
test -s "$OUT/EnthusiaTags/config.yml"

# The custom advancement presentation stack is intentionally excluded on 26.3.
# Its upstream NMS library does not yet support this server version. Durable
# challenge ownership is the context-free LuckPerms entitlement; presentation
# can be reconciled later when a compatible advancement renderer exists.
rm -f "$OUT/EnthusiaAdvancements-1.0.0-frontier.jar" "$OUT/UltimateAdvancementAPI-2.8.1.jar"

(
  cd "$ROOT"
  sha256sum \
    server/plugins/EnthusiaTempChallenges-0.2.0-frontier.1.jar \
    server/plugins/EnthusiaTags.jar \
    > server/PLUGIN-SHA256SUMS.txt
)
cat "$ROOT/server/PLUGIN-SHA256SUMS.txt"

echo 'Minimal Paper 26.3 Frontier challenge runtime built successfully.'
