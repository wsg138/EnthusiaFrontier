#!/usr/bin/env bash
set -euo pipefail
JAR="paper-26.3.jar"
[[ -s "$JAR" ]] || { echo "Required Paper 26.3 runtime is missing: $JAR" >&2; exit 1; }
mkdir -p logs
DEFAULT_JAVA_ARGS="-Xms32G -Xmx32G -XX:+UseZGC -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -Xlog:gc*:logs/gc-zgc.log:time,uptime,level,tags:filecount=5,filesize=10M -Dterminal.jline=false -Dterminal.ansi=true -Duser.timezone=America/Indiana/Indianapolis"
exec java ${JAVA_ARGS:-$DEFAULT_JAVA_ARGS} -jar "$JAR" --nogui
