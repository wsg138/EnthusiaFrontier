[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SourceSmpRoot,
    [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$BackendPort
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Utf8 = [System.Text.UTF8Encoding]::new($false)

function Set-Test2Identity {
    $propertiesPath = Join-Path $Root 'server.properties'
    $text = [System.IO.File]::ReadAllText($propertiesPath)
    $text = [regex]::Replace($text, '(?m)^motd=.*$', 'motd=Enthusia TEMP')
    $text = [regex]::Replace($text, '(?m)^server-name=.*$', 'server-name=Enthusia TEMP')
    [System.IO.File]::WriteAllText($propertiesPath, $text, $Utf8)
}

$manifest = Join-Path $Root 'BINARY-MANIFEST.yml'
if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) {
    throw 'BINARY-MANIFEST.yml is missing; refusing to prepare Test2.'
}

Set-Test2Identity
& (Join-Path $Root 'populate-runtime.ps1') `
    -SourceSmpRoot $SourceSmpRoot `
    -BackendPort $BackendPort

Write-Host 'TEST2_TEMP_RUNTIME_READY' -ForegroundColor Green
Write-Host 'Next: upload the prepared runtime contents into the empty Test2 server root, then complete TEST2-DEPLOYMENT.md.'
