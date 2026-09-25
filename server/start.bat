@echo off
set JAR=leaf-1.21.11-179.jar
if not exist "%JAR%" (
  echo Download %JAR% from the official Leaf ver-1.21.11 release before starting on Windows.
  exit /b 1
)
java -Xms2G -Xmx8G -jar "%JAR%" --nogui
