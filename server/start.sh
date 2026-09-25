#!/usr/bin/env bash
set -euo pipefail
JAR="leaf-1.21.11-179.jar"
URL="https://github.com/Winds-Studio/Leaf/releases/download/ver-1.21.11/leaf-1.21.11-179.jar"
SHA="5da79782215c1a25edcd7c73b3523b7ecb7f4b86dc8a5846a176ed69bc2cd020"
if [[ ! -f "$JAR" ]]; then
  echo "Leaf runtime missing; downloading verified Leaf 1.21.11 build 179..."
  curl --fail --location --retry 3 "$URL" -o "$JAR.tmp"
  echo "$SHA  $JAR.tmp" | sha256sum -c -
  mv "$JAR.tmp" "$JAR"
fi
ACTUAL="$(sha256sum "$JAR" | awk '{print $1}')"
[[ "$ACTUAL" == "$SHA" ]] || { echo "Leaf SHA-256 mismatch" >&2; exit 1; }
exec java ${JAVA_ARGS:--Xms2G -Xmx8G} -jar "$JAR" --nogui
