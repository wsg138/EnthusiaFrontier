@echo off
setlocal enabledelayedexpansion
set "JAR=leaf-1.21.11-179.jar"
set "URL=https://github.com/Winds-Studio/Leaf/releases/download/ver-1.21.11/leaf-1.21.11-179.jar"
set "SHA=5da79782215c1a25edcd7c73b3523b7ecb7f4b86dc8a5846a176ed69bc2cd020"

if not exist "%JAR%" (
  echo Leaf runtime missing; downloading verified Leaf 1.21.11 build 179...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing -Uri '%URL%' -OutFile '%JAR%.tmp'; $h=(Get-FileHash -Algorithm SHA256 '%JAR%.tmp').Hash.ToLowerInvariant(); if($h -ne '%SHA%'){ throw 'Leaf SHA-256 mismatch' }; Move-Item -Force '%JAR%.tmp' '%JAR%'"
  if errorlevel 1 exit /b 1
)

for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%JAR%').Hash.ToLowerInvariant()"') do set "ACTUAL=%%H"
if /I not "!ACTUAL!"=="%SHA%" (
  echo Leaf SHA-256 mismatch
  exit /b 1
)

if "%JAVA_ARGS%"=="" set "JAVA_ARGS=-Xms2G -Xmx8G"
java %JAVA_ARGS% -jar "%JAR%" --nogui
