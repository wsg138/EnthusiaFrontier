[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceSmpRoot,
    [string]$FrontierJarPath,
    [int]$BackendPort = 25568
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ServerRoot = $PSScriptRoot
$PluginsRoot = Join-Path $ServerRoot 'plugins'
$SourcePlugins = Join-Path $SourceSmpRoot 'plugins'

function Assert-Sha256 {
    param([string]$Path, [string]$Expected)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing source file: $Path" }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) {
        throw "SHA-256 mismatch for $Path`nExpected: $Expected`nActual:   $actual"
    }
}

function Copy-Verified {
    param([string]$Source, [string]$Destination, [string]$Sha256)
    Assert-Sha256 $Source $Sha256
    New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

function Get-PaperVelocitySecret {
    param([string]$Path)
    $lines = [System.IO.File]::ReadAllLines($Path)
    $insideVelocity = $false
    $velocityIndent = -1
    foreach ($line in $lines) {
        if (-not $insideVelocity) {
            if ($line -match '^(\s*)velocity:\s*$') {
                $insideVelocity = $true
                $velocityIndent = $Matches[1].Length
            }
            continue
        }
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        [void]($line -match '^(\s*)')
        $indent = $Matches[1].Length
        if ($indent -le $velocityIndent) { $insideVelocity = $false; continue }
        if ($line -match '^\s*secret:\s*(.+?)\s*$') {
            $secret = $Matches[1].Trim().Trim('"').Trim("'")
            if ([string]::IsNullOrWhiteSpace($secret) -or $secret -match '<REDACTED>|REPLACE_WITH') {
                throw 'Source Velocity forwarding secret is empty/redacted.'
            }
            return $secret
        }
    }
    throw "Could not find proxies.velocity.secret in $Path"
}

function Set-PaperVelocitySecret {
    param([string]$Path, [string]$Secret)
    $lines = [System.Collections.Generic.List[string]]::new()
    [System.IO.File]::ReadAllLines($Path) | ForEach-Object { [void]$lines.Add($_) }
    $insideVelocity = $false
    $velocityIndent = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if (-not $insideVelocity) {
            if ($line -match '^(\s*)velocity:\s*$') { $insideVelocity = $true; $velocityIndent = $Matches[1].Length }
            continue
        }
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        [void]($line -match '^(\s*)')
        $indent = $Matches[1].Length
        if ($indent -le $velocityIndent) { break }
        if ($line -match '^(\s*)secret:\s*') {
            $lines[$i] = $Matches[1] + 'secret: "' + $Secret.Replace('"','\"') + '"'
            [System.IO.File]::WriteAllLines($Path, $lines)
            return
        }
    }
    throw "Could not replace proxies.velocity.secret in $Path"
}

$copies = @(
    @{ S='CoreProtect-24.1.jar'; D='CoreProtect-24.1.jar'; H='a7137839a5b20d993e168381dee22136c4ca77979c9d5627ccbdb7c4058d737f' },
    @{ S='InventoryRollbackPlus-1.8.2.jar'; D='InventoryRollbackPlus-1.8.2.jar'; H='2caada5cd90e86767466dd67c5f1b9616adafdccfe561da44d84985e9ffac43d' },
    @{ S='EnthusiaPlaytime-3.7.2.jar'; D='EnthusiaPlaytime-3.7.2.jar'; H='d6d79b11588c9d60ece254798480e83b7c286b1909d138c803db11f6a1ec9344' },
    @{ S='LuckPerms-Bukkit-5.5.53.jar'; D='LuckPerms-Bukkit-5.5.53.jar'; H='fc8d4eccbf11c1e844af4527f018bbfde90c1866a9aba1bf880173a8e644cd59' },
    @{ S='floodgate-spigot.jar'; D='floodgate-spigot.jar'; H='21570aff9ce17d6983928e8552777760e1ede5050026b04c686b0ae112e6fd7e' },
    @{ S='BedrockWindChargeFix-1.0.0.jar'; D='BedrockWindChargeFix-1.0.0.jar'; H='ddd4583ed2b3ba9c90a8293936f4cb6ff8b990d4233646de6d80d91528155d8d' },
    @{ S='EnthusiaTags(1).jar'; D='EnthusiaTags.jar'; H='69aa6474c6de27e160d3ccfe33a56dd9674b60ec92ddb2658af3553cb272c83d' },
    @{ S='nexo-1.22.1.jar'; D='nexo-1.22.1.jar'; H='8771545bf1d29500641c29733a741f863eccbf7dbf43217691dee8de33d43bac' },
    @{ S='PlaceholderAPI-2.12.3.jar'; D='PlaceholderAPI-2.12.3.jar'; H='fde03259f5af6938f3c33eeb4d814000a1adabf1d2304ce14970be81f609a437' },
    @{ S='TAB v5.5.0.jar'; D='TAB-v5.5.0.jar'; H='829e7ec22bc41069d93b53479a8fe4a335a579c9c5b37a4cf140da176aea65f6' }
)
foreach ($item in $copies) {
    Copy-Verified (Join-Path $SourcePlugins $item.S) (Join-Path $PluginsRoot $item.D) $item.H
}

if (-not [string]::IsNullOrWhiteSpace($FrontierJarPath)) {
    Copy-Verified $FrontierJarPath (Join-Path $PluginsRoot 'EnthusiaFrontier-0.1.1.jar') '74b90cafbd96cdbd9a51d07bc897b223161eab87bb7014416d662250442aeefa'
}

# Copy private Floodgate identity material locally; it remains gitignored.
$sourceFloodgateKey = Join-Path $SourcePlugins 'floodgate\key.pem'
if (-not (Test-Path -LiteralPath $sourceFloodgateKey -PathType Leaf)) { throw "Missing Floodgate key: $sourceFloodgateKey" }
Copy-Item -LiteralPath $sourceFloodgateKey -Destination (Join-Path $PluginsRoot 'floodgate\key.pem') -Force

# Use the live LuckPerms network DB configuration but force this backend's server context.
$sourceLuckPerms = Join-Path $SourcePlugins 'LuckPerms\config.yml'
if (-not (Test-Path -LiteralPath $sourceLuckPerms -PathType Leaf)) { throw "Missing LuckPerms config: $sourceLuckPerms" }
$lpText = [System.IO.File]::ReadAllText($sourceLuckPerms)
if ($lpText -match '<REDACTED>') { throw 'LuckPerms source config is sanitized; use a raw live SMP copy.' }
$lpText = [regex]::Replace($lpText, '(?m)^server:\s*.*$', 'server: EnthusiaFrontierTest', 1)
[System.IO.File]::WriteAllText((Join-Path $PluginsRoot 'LuckPerms\config.yml'), $lpText)

# Copy the full Nexo asset/config directory from the live server so emoji textures and pack assets exist.
$sourceNexo = Join-Path $SourcePlugins 'Nexo'
if (-not (Test-Path -LiteralPath $sourceNexo -PathType Container)) { throw "Missing Nexo directory: $sourceNexo" }
$sourceNexoSettings = Join-Path $sourceNexo 'settings.yml'
$nexoSettingsText = [System.IO.File]::ReadAllText($sourceNexoSettings)
if ($nexoSettingsText -match '<REDACTED>') { throw 'Nexo source settings are sanitized; use a raw live SMP copy.' }
Copy-Item -LiteralPath $sourceNexo -Destination $PluginsRoot -Recurse -Force

# Copy existing Tags presentation config, but deliberately do not copy tags.db/player ownership state.
$sourceTags = Join-Path $SourcePlugins 'EnthusiaTags'
if (Test-Path -LiteralPath $sourceTags -PathType Container) {
    $destTags = Join-Path $PluginsRoot 'EnthusiaTags'
    New-Item -ItemType Directory -Path $destTags -Force | Out-Null
    foreach ($name in @('config.yml','cosmetics.yml','messages.yml','rewards.yml')) {
        $src = Join-Path $sourceTags $name
        if (Test-Path -LiteralPath $src -PathType Leaf) { Copy-Item -LiteralPath $src -Destination (Join-Path $destTags $name) -Force }
    }
}

# Reuse the network's modern forwarding secret without printing it.
$secret = Get-PaperVelocitySecret (Join-Path $SourceSmpRoot 'config\paper-global.yml')
Set-PaperVelocitySecret (Join-Path $ServerRoot 'config\paper-global.yml') $secret

# Apply the actual backend allocation.
$propertiesPath = Join-Path $ServerRoot 'server.properties'
$props = [System.IO.File]::ReadAllText($propertiesPath)
$props = [regex]::Replace($props, '(?m)^server-port=.*$', "server-port=$BackendPort")
$props = [regex]::Replace($props, '(?m)^query\.port=.*$', "query.port=$BackendPort")
[System.IO.File]::WriteAllText($propertiesPath, $props)

Write-Host 'Frontier Test runtime populated from the live SMP files.' -ForegroundColor Green
Write-Host 'Challenge/advancement JARs remain owned by the challenge worker.'
Write-Host 'Run .\validate-runtime.ps1 before opening the server.'
