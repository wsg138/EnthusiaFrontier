[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot

function Require-File([string]$Relative) {
    $path = Join-Path $Root $Relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing required file: $Relative" }
    return $path
}
function Check-Hash([string]$Relative, [string]$Expected) {
    $path = Require-File $Relative
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) { throw "Hash mismatch: $Relative`nExpected $Expected`nActual   $actual" }
}

Require-File 'server.properties' | Out-Null
Require-File 'config\paper-global.yml' | Out-Null
Require-File 'config\paper-world-defaults.yml' | Out-Null
Require-File 'config\leaf-global.yml' | Out-Null
Require-File 'world\datapacks\enthusia-frontier-test\pack.mcmeta' | Out-Null

$leaf = [System.IO.File]::ReadAllText((Join-Path $Root 'config\leaf-global.yml'))
if ($leaf -notmatch '(?s)secure-seed:\s*\r?\n\s*enabled:\s*true') { throw 'Leaf Secure Seed is not enabled.' }

foreach ($relative in @('config\paper-global.yml','plugins\LuckPerms\config.yml','plugins\Nexo\settings.yml')) {
    $text = [System.IO.File]::ReadAllText((Join-Path $Root $relative))
    if ($text -match 'REPLACE_WITH|<REDACTED>') { throw "Deployment placeholder remains in $relative" }
}
Require-File 'plugins\floodgate\key.pem' | Out-Null

$known = @{
    'plugins\EnthusiaFrontier-0.1.1.jar'='74b90cafbd96cdbd9a51d07bc897b223161eab87bb7014416d662250442aeefa'
    'plugins\CoreProtect-24.1.jar'='a7137839a5b20d993e168381dee22136c4ca77979c9d5627ccbdb7c4058d737f'
    'plugins\InventoryRollbackPlus-1.8.2.jar'='2caada5cd90e86767466dd67c5f1b9616adafdccfe561da44d84985e9ffac43d'
    'plugins\EnthusiaPlaytime-3.7.2.jar'='d6d79b11588c9d60ece254798480e83b7c286b1909d138c803db11f6a1ec9344'
    'plugins\LuckPerms-Bukkit-5.5.53.jar'='fc8d4eccbf11c1e844af4527f018bbfde90c1866a9aba1bf880173a8e644cd59'
    'plugins\floodgate-spigot.jar'='21570aff9ce17d6983928e8552777760e1ede5050026b04c686b0ae112e6fd7e'
    'plugins\BedrockWindChargeFix-1.0.0.jar'='ddd4583ed2b3ba9c90a8293936f4cb6ff8b990d4233646de6d80d91528155d8d'
    'plugins\EnthusiaTags.jar'='69aa6474c6de27e160d3ccfe33a56dd9674b60ec92ddb2658af3553cb272c83d'
    'plugins\nexo-1.22.1.jar'='8771545bf1d29500641c29733a741f863eccbf7dbf43217691dee8de33d43bac'
    'plugins\PlaceholderAPI-2.12.3.jar'='fde03259f5af6938f3c33eeb4d814000a1adabf1d2304ce14970be81f609a437'
    'plugins\TAB-v5.5.0.jar'='829e7ec22bc41069d93b53479a8fe4a335a579c9c5b37a4cf140da176aea65f6'
}
foreach ($entry in $known.GetEnumerator()) { Check-Hash $entry.Key $entry.Value }

$plugins = Join-Path $Root 'plugins'
foreach ($pattern in @('Chunky*.jar','LumaGuilds*.jar','EnthusiaTeleport*.jar')) {
    if (Get-ChildItem -LiteralPath $plugins -Filter $pattern -File -ErrorAction SilentlyContinue) { throw "Forbidden minimal-server plugin present: $pattern" }
}

if (-not (Get-ChildItem -LiteralPath $plugins -Filter 'EnthusiaTempChallenges*.jar' -File -ErrorAction SilentlyContinue)) { throw 'Challenge worker JAR is not present yet.' }
if (-not (Get-ChildItem -LiteralPath $plugins -Filter 'EnthusiaAdvancements*.jar' -File -ErrorAction SilentlyContinue)) { throw 'EnthusiaAdvancements JAR is not present yet.' }
if (-not (Get-ChildItem -LiteralPath $plugins -Filter 'UltimateAdvancementAPI*.jar' -File -ErrorAction SilentlyContinue)) { throw 'UltimateAdvancementAPI JAR is not present yet.' }

Write-Host 'FRONTIER_TEST_RUNTIME_READY' -ForegroundColor Green
