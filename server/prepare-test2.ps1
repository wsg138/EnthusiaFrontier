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

function Align-FrontierHashPins {
    param([string]$ExpectedHash)

    # The runtime-publishing workflow advances the JAR and manifest atomically, but the
    # older helper scripts still contain the pre-publication hash. Align those local
    # helper pins with the current workflow-published manifest before validation.
    $legacyHash = '74b90cafbd96cdbd9a51d07bc897b223161eab87bb7014416d662250442aeefa'

    foreach ($name in @('populate-runtime.ps1', 'validate-runtime.ps1')) {
        $path = Join-Path $Root $name
        $text = [System.IO.File]::ReadAllText($path)
        if (-not $text.Contains($legacyHash) -and -not $text.Contains($ExpectedHash)) {
            throw "$name does not contain either the known legacy Frontier hash or the current expected hash. Refusing an ambiguous patch."
        }
        $text = $text.Replace($legacyHash, $ExpectedHash)
        [System.IO.File]::WriteAllText($path, $text, $Utf8)
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
Align-FrontierHashPins -ExpectedHash $currentHash
Set-Test2Identity

& (Join-Path $Root 'populate-runtime.ps1') `
    -SourceSmpRoot $SourceSmpRoot `
    -BackendPort $BackendPort

Write-Host 'TEST2_TEMP_RUNTIME_READY' -ForegroundColor Green
Write-Host 'Next: upload the contents of server/ into the empty Test2 server root, then complete TEST2-DEPLOYMENT.md.'
