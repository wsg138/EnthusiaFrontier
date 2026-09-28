@echo off
setlocal
set "JAR=paper-26.3.jar"
if not exist "%JAR%" (
  echo Required Paper 26.3 runtime is missing: %JAR%
  exit /b 1
)
if not exist "logs" mkdir "logs"
if "%JAVA_ARGS%"=="" set "JAVA_ARGS=-Xms32G -Xmx32G -XX:+UseZGC -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -Xlog:gc*:logs/gc-zgc.log:time,uptime,level,tags:filecount=5,filesize=10M -Dterminal.jline=false -Dterminal.ansi=true -Duser.timezone=America/Indiana/Indianapolis"
java %JAVA_ARGS% -jar "%JAR%" --nogui
