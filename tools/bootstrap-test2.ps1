[CmdletBinding()]
param(
    [ValidateRange(1, 65535)][int]$BackendPort = 25566,
    [string]$Branch = 'server/frontier-test-runtime'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$WorkRoot = Join-Path $env:LOCALAPPDATA 'Enthusia-TEMP-Deploy'
$RepoRoot = Join-Path $WorkRoot 'EnthusiaFrontier'
$Desktop = [Environment]::GetFolderPath('Desktop')
$FinalZip = Join-Path $Desktop 'Test2-TEMP-runtime.zip'
$PowerShellEngine = (Get-Process -Id $PID).Path

function Require-Command([string]$Name) {
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $cmd) { throw "Required command '$Name' was not found in PATH." }
    return $cmd.Source
}

$git = Require-Command 'git.exe'
New-Item -ItemType Directory -Path $WorkRoot -Force | Out-Null

if (Test-Path -LiteralPath $RepoRoot) {
    Write-Host 'Refreshing existing local deployment checkout...'
    & $git -C $RepoRoot fetch --prune origin $Branch
    if ($LASTEXITCODE -ne 0) { throw 'git fetch failed.' }
    & $git -C $RepoRoot checkout -B $Branch "origin/$Branch"
    if ($LASTEXITCODE -ne 0) { throw 'git checkout/reset failed.' }
    & $git -C $RepoRoot clean -fdx
    if ($LASTEXITCODE -ne 0) { throw 'git clean failed.' }
} else {
    Write-Host 'Cloning exact EnthusiaFrontier TEMP runtime branch...'
    & $git clone --branch $Branch --single-branch 'https://github.com/wsg138/EnthusiaFrontier.git' $RepoRoot
    if ($LASTEXITCODE -ne 0) { throw 'git clone failed.' }
}

Write-Host "Preparing Test2 runtime for backend port $BackendPort..."
& $PowerShellEngine -NoProfile -ExecutionPolicy Bypass -File (Join-Path $RepoRoot 'tools\prepare-test2-local.ps1') -BackendPort $BackendPort -CreateZip
if ($LASTEXITCODE -ne 0) { throw 'Test2 runtime preparation failed.' }

$BuiltZip = Join-Path $RepoRoot 'build\Test2-TEMP-runtime.zip'
if (-not (Test-Path -LiteralPath $BuiltZip -PathType Leaf)) { throw "Expected deployment ZIP was not created: $BuiltZip" }
Copy-Item -LiteralPath $BuiltZip -Destination $FinalZip -Force

$sha = (Get-FileHash -LiteralPath $FinalZip -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host ''
Write-Host 'TEST2 TEMP PACKAGE READY' -ForegroundColor Green
Write-Host 'Upload this ZIP to the empty Bloom Test2 root and extract it there:'
Write-Host $FinalZip -ForegroundColor Cyan
Write-Host "SHA-256: $sha"
Write-Host ''
Write-Host 'Keep the Bloom Java runtime set to Java 25 for Minecraft/Paper 26.3.' -ForegroundColor Yellow
