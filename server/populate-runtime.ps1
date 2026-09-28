[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SourceSmpRoot,
    [string]$FrontierJarPath,
    [ValidateRange(1, 65535)][int]$BackendPort = 25568
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ServerRoot = $PSScriptRoot
$PluginsRoot = Join-Path $ServerRoot 'plugins'
$SourcePlugins = Join-Path $SourceSmpRoot 'plugins'
$ManifestPath = Join-Path $ServerRoot 'BINARY-MANIFEST.yml'
$ManifestText = [System.IO.File]::ReadAllText($ManifestPath)

function Assert-Sha256 {
    param([string]$Path, [string]$Expected)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing file: $Path" }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) {
        throw "SHA-256 mismatch for $Path`nExpected: $Expected`nActual:   $actual"
    }
}

function Get-ManifestPluginHash([string]$Name) {
    $escaped = [regex]::Escape($Name)
    $match = [regex]::Match($ManifestText, "(?m)^  $escaped`:\s*([0-9a-f]{64})\s*$")
    if (-not $match.Success) { throw "BINARY-MANIFEST.yml has no hash for $Name" }
    return $match.Groups[1].Value
}

function Get-ManifestRuntime {
    $match = [regex]::Match($ManifestText, '(?ms)^runtime:\s*\r?\n\s*java:\s*(\d+)\s*\r?\n\s*server:\s*\r?\n\s*file:\s*([^\r\n]+)\s*\r?\n\s*sha256:\s*([0-9a-f]{64})')
    if (-not $match.Success) { throw 'Could not parse runtime server from BINARY-MANIFEST.yml.' }
    return @{ Java=[int]$match.Groups[1].Value; File=$match.Groups[2].Value.Trim(); Hash=$match.Groups[3].Value }
}

function Copy-Verified {
    param([string]$Source, [string]$Destination, [string]$Sha256)
    Assert-Sha256 $Source $Sha256
    New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

function Get-ChallengeArtifactHashes {
    $hashFile = Join-Path $ServerRoot 'PLUGIN-SHA256SUMS.txt'
    if (-not (Test-Path -LiteralPath $hashFile -PathType Leaf)) { throw "Missing challenge hash manifest: $hashFile" }
    $result = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($hashFile)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line -notmatch '^([0-9a-fA-F]{64})\s+(.+)$') { throw "Malformed challenge hash line: $line" }
        $result[[System.IO.Path]::GetFileName($Matches[2].Trim())] = $Matches[1].ToLowerInvariant()
    }
    return $result
}

function Get-PaperVelocitySecret {
    param([string]$Path)
    $lines = [System.IO.File]::ReadAllLines($Path)
    $insideVelocity = $false; $velocityIndent = -1
    foreach ($line in $lines) {
        if (-not $insideVelocity) {
            if ($line -match '^(\s*)velocity:\s*$') { $insideVelocity = $true; $velocityIndent = $Matches[1].Length }
            continue
        }
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        [void]($line -match '^(\s*)'); $indent = $Matches[1].Length
        if ($indent -le $velocityIndent) { $insideVelocity = $false; continue }
        if ($line -match '^\s*secret:\s*(.+?)\s*$') {
            $secret = $Matches[1].Trim().Trim('"').Trim("'")
            if ([string]::IsNullOrWhiteSpace($secret) -or $secret -match '<REDACTED>|REPLACE_WITH') { throw 'Source Velocity forwarding secret is empty/redacted.' }
            return $secret
        }
    }
    throw "Could not find proxies.velocity.secret in $Path"
}

function Set-PaperVelocitySecret {
    param([string]$Path, [string]$Secret)
    $lines = [System.Collections.Generic.List[string]]::new()
    [System.IO.File]::ReadAllLines($Path) | ForEach-Object { [void]$lines.Add($_) }
    $insideVelocity = $false; $velocityIndent = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if (-not $insideVelocity) {
            if ($line -match '^(\s*)velocity:\s*$') { $insideVelocity = $true; $velocityIndent = $Matches[1].Length }
            continue
        }
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        [void]($line -match '^(\s*)'); $indent = $Matches[1].Length
        if ($indent -le $velocityIndent) { break }
        if ($line -match '^(\s*)secret:\s*') {
            $lines[$i] = $Matches[1] + 'secret: "' + $Secret.Replace('"','\"') + '"'
            [System.IO.File]::WriteAllLines($Path, $lines)
            return
        }
    }
    throw "Could not replace proxies.velocity.secret in $Path"
}

$runtime = Get-ManifestRuntime
if ($runtime.Java -ne 25 -or $runtime.File -ne 'paper-26.3.jar') {
    throw "Runtime manifest is not the approved Paper 26.3 / Java 25 baseline: $($runtime.File), Java $($runtime.Java)"
}
Assert-Sha256 (Join-Path $ServerRoot $runtime.File) $runtime.Hash

$challengeArtifacts = Get-ChallengeArtifactHashes
$requiredChallengeArtifacts = @('EnthusiaTempChallenges-0.2.0-frontier.1.jar','EnthusiaTags.jar')
foreach ($name in $requiredChallengeArtifacts) {
    if (-not $challengeArtifacts.ContainsKey($name)) { throw "Challenge hash manifest does not contain required artifact: $name" }
    Assert-Sha256 (Join-Path $PluginsRoot $name) $challengeArtifacts[$name]
    $manifestHash = Get-ManifestPluginHash $name
    if ($manifestHash -ne $challengeArtifacts[$name]) { throw "Manifest/hash-file disagreement for $name" }
}
foreach ($unsupported in @('EnthusiaAdvancements-1.0.0-frontier.jar','UltimateAdvancementAPI-2.8.1.jar')) {
    if (Test-Path -LiteralPath (Join-Path $PluginsRoot $unsupported)) { throw "Unsupported 26.3 presentation plugin remains: $unsupported" }
}
if (-not (Test-Path -LiteralPath (Join-Path $PluginsRoot 'EnthusiaTags\config.yml') -PathType Leaf)) { throw 'Frontier-specific EnthusiaTags config is missing.' }

$copies = @(
    @{ S='CoreProtect-24.1.jar'; D='CoreProtect-24.1.jar' },
    @{ S='InventoryRollbackPlus-1.8.2.jar'; D='InventoryRollbackPlus-1.8.2.jar' },
    @{ S='EnthusiaPlaytime-3.7.2.jar'; D='EnthusiaPlaytime-3.7.2.jar' },
    @{ S='LuckPerms-Bukkit-5.5.53.jar'; D='LuckPerms-Bukkit-5.5.53.jar' },
    @{ S='floodgate-spigot.jar'; D='floodgate-spigot.jar' },
    @{ S='BedrockWindChargeFix-1.0.0.jar'; D='BedrockWindChargeFix-1.0.0.jar' },
    @{ S='nexo-1.22.1.jar'; D='nexo-1.22.1.jar' },
    @{ S='PlaceholderAPI-2.12.3.jar'; D='PlaceholderAPI-2.12.3.jar' },
    @{ S='TAB v5.5.0.jar'; D='TAB-v5.5.0.jar' }
)
foreach ($item in $copies) {
    Copy-Verified (Join-Path $SourcePlugins $item.S) (Join-Path $PluginsRoot $item.D) (Get-ManifestPluginHash $item.D)
}

$frontierTarget = Join-Path $PluginsRoot 'EnthusiaFrontier-0.1.1.jar'
$frontierSha = Get-ManifestPluginHash 'EnthusiaFrontier-0.1.1.jar'
if (-not [string]::IsNullOrWhiteSpace($FrontierJarPath)) { Copy-Verified $FrontierJarPath $frontierTarget $frontierSha }
Assert-Sha256 $frontierTarget $frontierSha

$sourceFloodgateKey = Join-Path $SourcePlugins 'floodgate\key.pem'
if (-not (Test-Path -LiteralPath $sourceFloodgateKey -PathType Leaf)) { throw "Missing Floodgate key: $sourceFloodgateKey" }
New-Item -ItemType Directory -Path (Join-Path $PluginsRoot 'floodgate') -Force | Out-Null
Copy-Item -LiteralPath $sourceFloodgateKey -Destination (Join-Path $PluginsRoot 'floodgate\key.pem') -Force

$sourceLuckPerms = Join-Path $SourcePlugins 'LuckPerms\config.yml'
if (-not (Test-Path -LiteralPath $sourceLuckPerms -PathType Leaf)) { throw "Missing LuckPerms config: $sourceLuckPerms" }
$lpText = [System.IO.File]::ReadAllText($sourceLuckPerms)
if ($lpText -match '<REDACTED>|REPLACE_WITH') { throw 'LuckPerms source config is sanitized; use a raw live SMP copy.' }
$lpText = [regex]::Replace($lpText, '(?m)^server:\s*.*$', 'server: EnthusiaTEMP', 1)
New-Item -ItemType Directory -Path (Join-Path $PluginsRoot 'LuckPerms') -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $PluginsRoot 'LuckPerms\config.yml'), $lpText)

$sourceNexo = Join-Path $SourcePlugins 'Nexo'
if (-not (Test-Path -LiteralPath $sourceNexo -PathType Container)) { throw "Missing Nexo directory: $sourceNexo" }
$sourceNexoSettings = Join-Path $sourceNexo 'settings.yml'
$nexoSettingsText = [System.IO.File]::ReadAllText($sourceNexoSettings)
if ($nexoSettingsText -match '<REDACTED>|REPLACE_WITH') { throw 'Nexo source settings are sanitized; use a raw live SMP copy.' }
$destNexo = Join-Path $PluginsRoot 'Nexo'
if (Test-Path -LiteralPath $destNexo) { Remove-Item -LiteralPath $destNexo -Recurse -Force }
Copy-Item -LiteralPath $sourceNexo -Destination $destNexo -Recurse -Force

$secret = Get-PaperVelocitySecret (Join-Path $SourceSmpRoot 'config\paper-global.yml')
Set-PaperVelocitySecret (Join-Path $ServerRoot 'config\paper-global.yml') $secret

$propertiesPath = Join-Path $ServerRoot 'server.properties'
$props = [System.IO.File]::ReadAllText($propertiesPath)
$props = [regex]::Replace($props, '(?m)^server-port=.*$', "server-port=$BackendPort")
$props = [regex]::Replace($props, '(?m)^query\.port=.*$', "query.port=$BackendPort")
[System.IO.File]::WriteAllText($propertiesPath, $props)

Write-Host 'TEMP runtime populated from the live SMP files.' -ForegroundColor Green
Write-Host 'Paper 26.3, Frontier, TEMP challenges and Tags are manifest/hash verified.'
Write-Host 'Running final static runtime validation...'
& (Join-Path $ServerRoot 'validate-runtime.ps1')
