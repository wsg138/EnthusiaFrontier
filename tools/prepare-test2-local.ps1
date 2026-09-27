[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][int]$BackendPort,
    [string]$SourceRemote = 'bloom-smp',
    [string]$RclonePath,
    [switch]$CreateZip
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$TemplateRoot = Join-Path $RepoRoot 'server'
$BuildRoot = Join-Path $RepoRoot 'build'
$OutputRoot = Join-Path $BuildRoot 'test2-runtime'
$StageRoot = Join-Path $env:TEMP ("Enthusia-TEMP-smp-source-" + [guid]::NewGuid().ToString('N'))

function Resolve-Rclone {
    if (-not [string]::IsNullOrWhiteSpace($RclonePath)) {
        if (-not (Test-Path -LiteralPath $RclonePath -PathType Leaf)) { throw "rclone not found: $RclonePath" }
        return (Resolve-Path -LiteralPath $RclonePath).Path
    }
    $cmd = Get-Command rclone.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $known = Join-Path $env:LOCALAPPDATA 'Temp\Enthusia-Test-Maintenance\rclone.exe'
    if (Test-Path -LiteralPath $known -PathType Leaf) { return $known }
    throw 'Could not find rclone.exe. Pass -RclonePath with its full path.'
}

function Invoke-Rclone([string[]]$Arguments) {
    & $script:Rclone @Arguments
    if ($LASTEXITCODE -ne 0) { throw "rclone failed with exit code $LASTEXITCODE" }
}

function Copy-RemoteFile([string]$RelativePath) {
    $destination = Join-Path $StageRoot ($RelativePath -replace '/', '\')
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Invoke-Rclone @('copyto', "${SourceRemote}:$RelativePath", $destination, '--no-traverse', '--retries', '3', '--low-level-retries', '10')
}

$Rclone = Resolve-Rclone
$SourceRemote = $SourceRemote.TrimEnd(':')
$remotes = & $Rclone listremotes
if ($LASTEXITCODE -ne 0) { throw 'Could not list configured rclone remotes.' }
if (-not ($remotes -contains ($SourceRemote + ':'))) { throw "Configured rclone remote '$SourceRemote' was not found." }

New-Item -ItemType Directory -Path $BuildRoot -Force | Out-Null
if (Test-Path -LiteralPath $OutputRoot) { Remove-Item -LiteralPath $OutputRoot -Recurse -Force }
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
Copy-Item -Path (Join-Path $TemplateRoot '*') -Destination $OutputRoot -Recurse -Force

try {
    New-Item -ItemType Directory -Path $StageRoot -Force | Out-Null
    Write-Host "Reading approved deployment inputs from rclone remote '$SourceRemote'..."

    foreach ($relative in @(
        'config/paper-global.yml',
        'plugins/CoreProtect-24.1.jar',
        'plugins/InventoryRollbackPlus-1.8.2.jar',
        'plugins/EnthusiaPlaytime-3.7.2.jar',
        'plugins/LuckPerms-Bukkit-5.5.53.jar',
        'plugins/floodgate-spigot.jar',
        'plugins/BedrockWindChargeFix-1.0.0.jar',
        'plugins/nexo-1.22.1.jar',
        'plugins/PlaceholderAPI-2.12.3.jar',
        'plugins/TAB v5.5.0.jar',
        'plugins/floodgate/key.pem',
        'plugins/LuckPerms/config.yml'
    )) { Copy-RemoteFile $relative }

    $nexoDestination = Join-Path $StageRoot 'plugins\Nexo'
    New-Item -ItemType Directory -Path $nexoDestination -Force | Out-Null
    Invoke-Rclone @('copy', "${SourceRemote}:plugins/Nexo", $nexoDestination, '--retries', '3', '--low-level-retries', '10')

    & (Join-Path $OutputRoot 'prepare-test2.ps1') -SourceSmpRoot $StageRoot -BackendPort $BackendPort
    if ($LASTEXITCODE -ne 0) { throw "prepare-test2.ps1 failed with exit code $LASTEXITCODE" }

    Write-Host "Prepared deployable Test2 root: $OutputRoot" -ForegroundColor Green

    if ($CreateZip) {
        $zip = Join-Path $BuildRoot 'Test2-TEMP-runtime.zip'
        if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
        Compress-Archive -Path (Join-Path $OutputRoot '*') -DestinationPath $zip -CompressionLevel Optimal
        Write-Host "Created local deployment archive: $zip" -ForegroundColor Green
    }
}
finally {
    if (Test-Path -LiteralPath $StageRoot) { Remove-Item -LiteralPath $StageRoot -Recurse -Force -ErrorAction SilentlyContinue }
}

Write-Host 'Production SMP was read only; no remote delete/move/sync operations are used by this script.'
