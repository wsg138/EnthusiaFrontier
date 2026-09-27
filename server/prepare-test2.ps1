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

    $populatePath = Join-Path $Root 'populate-runtime.ps1'
    $populate = [System.IO.File]::ReadAllText($populatePath)
    $populate = [regex]::Replace(
        $populate,
        "Copy-Verified \$FrontierJarPath \$frontierTarget '[0-9a-f]{64}'",
        "Copy-Verified `$FrontierJarPath `$frontierTarget '$ExpectedHash'"
    )
    $populate = [regex]::Replace(
        $populate,
        "Assert-Sha256 \$frontierTarget '[0-9a-f]{64}'",
        "Assert-Sha256 `$frontierTarget '$ExpectedHash'"
    )
    [System.IO.File]::WriteAllText($populatePath, $populate, $Utf8)

    $validatorPath = Join-Path $Root 'validate-runtime.ps1'
    $validator = [System.IO.File]::ReadAllText($validatorPath)
    $validator = [regex]::Replace(
        $validator,
        "('plugins\\\\EnthusiaFrontier-0\.1\.1\.jar'=)'[0-9a-f]{64}'",
        "`$1'$ExpectedHash'"
    )
    [System.IO.File]::WriteAllText($validatorPath, $validator, $Utf8)
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
