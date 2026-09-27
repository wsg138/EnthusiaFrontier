[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SourceSmpRoot,
    [Parameter(Mandatory = $true)][int]$BackendPort
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Utf8 = [System.Text.UTF8Encoding]::new($false)

function Get-CurrentFrontierHash {
    $manifest = Join-Path $Root 'BINARY-MANIFEST.yml'
    $text = [System.IO.File]::ReadAllText($manifest)
    $match = [regex]::Match($text, '(?m)^  EnthusiaFrontier-0\.1\.1\.jar:\s*([0-9a-f]{64})\s*$')
    if (-not $match.Success) { throw 'Could not resolve the current Frontier runtime SHA-256 from BINARY-MANIFEST.yml.' }
    return $match.Groups[1].Value
}

function Assert-FrontierHelpersMatchManifest {
    param([string]$ExpectedHash)

    foreach ($name in @('populate-runtime.ps1', 'validate-runtime.ps1')) {
        $path = Join-Path $Root $name
        $text = [System.IO.File]::ReadAllText($path)
        if (-not $text.Contains($ExpectedHash)) {
            throw "$name is not pinned to the Frontier runtime hash declared by BINARY-MANIFEST.yml. Refusing to prepare Test2."
        }
    }
}

function Set-Test2Identity {
    $propertiesPath = Join-Path $Root 'server.properties'
    $text = [System.IO.File]::ReadAllText($propertiesPath)
    $text = [regex]::Replace($text, '(?m)^motd=.*$', 'motd=Enthusia TEMP')
    $text = [regex]::Replace($text, '(?m)^server-name=.*$', 'server-name=Enthusia TEMP')
    [System.IO.File]::WriteAllText($propertiesPath, $text, $Utf8)
}

$currentHash = Get-CurrentFrontierHash
Write-Host "Using current Frontier runtime SHA-256: $currentHash"
Assert-FrontierHelpersMatchManifest -ExpectedHash $currentHash
Set-Test2Identity

& (Join-Path $Root 'populate-runtime.ps1') `
    -SourceSmpRoot $SourceSmpRoot `
    -BackendPort $BackendPort

Write-Host 'TEST2_TEMP_RUNTIME_READY' -ForegroundColor Green
Write-Host 'Next: upload the contents of server/ into the empty Test2 server root, then complete TEST2-DEPLOYMENT.md.'
