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
function Get-ChallengeArtifactHashes {
    $hashFile = Require-File 'PLUGIN-SHA256SUMS.txt'
    $result = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($hashFile)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line -notmatch '^([0-9a-fA-F]{64})\s+(.+)$') { throw "Malformed challenge hash line: $line" }
        $name = [System.IO.Path]::GetFileName($Matches[2].Trim())
        $result[$name] = $Matches[1].ToLowerInvariant()
    }
    return $result
}

foreach ($relative in @(
    'eula.txt',
    'server.properties',
    'bukkit.yml',
    'spigot.yml',
    'purpur.yml',
    'config\paper-global.yml',
    'config\paper-world-defaults.yml',
    'config\leaf-global.yml',
    'config\gale-global.yml',
    'config\gale-world-defaults.yml',
    'plugins\PlaceholderAPI\config.yml',
    'plugins\TAB\config.yml',
    'plugins\EnthusiaTempChallenges\config.yml',
    'plugins\EnthusiaTags\config.yml',
    'plugins\EnthusiaAdvancements\trees\frontier_firsts.conf',
    'world\datapacks\enthusia-frontier-test\pack.mcmeta',
    'world\datapacks\enthusia-frontier-test\data\enthusia_test\function\load.mcfunction',
    'world\datapacks\enthusia-frontier-test\data\enthusia_test\function\tick.mcfunction',
    'world\datapacks\enthusia-frontier-test\data\minecraft\tags\function\load.json',
    'world\datapacks\enthusia-frontier-test\data\minecraft\tags\function\tick.json'
)) { Require-File $relative | Out-Null }

$leaf = [System.IO.File]::ReadAllText((Join-Path $Root 'config\leaf-global.yml'))
if ($leaf -notmatch '(?s)secure-seed:\s*\r?\n\s*enabled:\s*true') { throw 'Leaf Secure Seed is not enabled.' }

$properties = [System.IO.File]::ReadAllText((Join-Path $Root 'server.properties'))
if ($properties -notmatch '(?m)^online-mode=false\s*$') { throw 'Backend must remain offline-mode behind Velocity modern forwarding.' }
if ($properties -notmatch '(?m)^max-world-size=5000\s*$') { throw 'max-world-size must allow the full +/-5000 Overworld.' }
if ($properties -notmatch '(?m)^initial-enabled-packs=vanilla,file/enthusia-frontier-test\s*$') { throw 'Frontier bootstrap datapack must be explicitly enabled for first world creation.' }
if ($properties -notmatch '(?m)^feature-level-seed=\s*$') {
    Write-Warning 'feature-level-seed is populated or missing. Keep the live value private and verify this was intentional.'
}

$borderFunction = [System.IO.File]::ReadAllText((Join-Path $Root 'world\datapacks\enthusia-frontier-test\data\enthusia_test\function\load.mcfunction'))
if ($borderFunction -notmatch 'execute in minecraft:overworld run worldborder set 10000(?:\s|$)') { throw 'Overworld border must be 10000 wide (+/-5000).' }
if ($borderFunction -notmatch 'execute in minecraft:the_nether run worldborder set 5000(?:\s|$)') { throw 'Nether border must be 5000 wide (+/-2500).' }
if ($borderFunction -notmatch 'execute in minecraft:the_end run worldborder set 5000(?:\s|$)') { throw 'End border must be 5000 wide (+/-2500).' }

$elytraPolicy = [System.IO.File]::ReadAllText((Join-Path $Root 'world\datapacks\enthusia-frontier-test\data\enthusia_test\function\tick.mcfunction'))
if ($elytraPolicy -notmatch 'item_frame.*minecraft:elytra') { throw 'No-Elytra policy is missing the End Ship item-frame guard.' }
if ($elytraPolicy -notmatch 'clear @a minecraft:elytra') { throw 'No-Elytra policy is missing the player inventory guard.' }
$tickTag = [System.IO.File]::ReadAllText((Join-Path $Root 'world\datapacks\enthusia-frontier-test\data\minecraft\tags\function\tick.json'))
if ($tickTag -notmatch 'enthusia_test:tick') { throw 'No-Elytra tick function is not registered.' }

$challengeConfig = [System.IO.File]::ReadAllText((Join-Path $Root 'plugins\EnthusiaTempChallenges\config.yml'))
if ($challengeConfig -notmatch '(?s)first_elytra:.*?locked:\s*true') { throw 'First Elytra challenge must remain locked.' }
if ($challengeConfig -notmatch '(?m)^\s*state:\s*ACTIVE\s*$') { throw 'Frontier challenge event is not ACTIVE.' }

$tab = [System.IO.File]::ReadAllText((Join-Path $Root 'plugins\TAB\config.yml'))
foreach ($forbidden in @('%lumaguilds_', '%vault_eco_', '%enthusiarep_', '%floodgate%', '%nexo_')) {
    if ($tab.Contains($forbidden)) { throw "TAB config still contains dependency-sensitive placeholder: $forbidden" }
}

foreach ($relative in @('config\paper-global.yml','plugins\LuckPerms\config.yml','plugins\Nexo\settings.yml')) {
    $text = [System.IO.File]::ReadAllText((Join-Path $Root $relative))
    if ($text -match 'REPLACE_WITH|<REDACTED>') { throw "Deployment placeholder remains in $relative" }
}
Require-File 'plugins\floodgate\key.pem' | Out-Null

Check-Hash 'leaf-1.21.11-179.jar' '5da79782215c1a25edcd7c73b3523b7ecb7f4b86dc8a5846a176ed69bc2cd020'

$known = @{
    'plugins\EnthusiaFrontier-0.1.1.jar'='74b90cafbd96cdbd9a51d07bc897b223161eab87bb7014416d662250442aeefa'
    'plugins\CoreProtect-24.1.jar'='a7137839a5b20d993e168381dee22136c4ca77979c9d5627ccbdb7c4058d737f'
    'plugins\InventoryRollbackPlus-1.8.2.jar'='2caada5cd90e86767466dd67c5f1b9616adafdccfe561da44d84985e9ffac43d'
    'plugins\EnthusiaPlaytime-3.7.2.jar'='d6d79b11588c9d60ece254798480e83b7c286b1909d138c803db11f6a1ec9344'
    'plugins\LuckPerms-Bukkit-5.5.53.jar'='fc8d4eccbf11c1e844af4527f018bbfde90c1866a9aba1bf880173a8e644cd59'
    'plugins\floodgate-spigot.jar'='21570aff9ce17d6983928e8552777760e1ede5050026b04c686b0ae112e6fd7e'
    'plugins\BedrockWindChargeFix-1.0.0.jar'='ddd4583ed2b3ba9c90a8293936f4cb6ff8b990d4233646de6d80d91528155d8d'
    'plugins\nexo-1.22.1.jar'='8771545bf1d29500641c29733a741f863eccbf7dbf43217691dee8de33d43bac'
    'plugins\PlaceholderAPI-2.12.3.jar'='fde03259f5af6938f3c33eeb4d814000a1adabf1d2304ce14970be81f609a437'
    'plugins\TAB-v5.5.0.jar'='829e7ec22bc41069d93b53479a8fe4a335a579c9c5b37a4cf140da176aea65f6'
}
foreach ($entry in $known.GetEnumerator()) { Check-Hash $entry.Key $entry.Value }

$challengeArtifacts = Get-ChallengeArtifactHashes
$requiredChallengeArtifacts = @(
    'EnthusiaTempChallenges-0.2.0-frontier.1.jar',
    'EnthusiaAdvancements-1.0.0-frontier.jar',
    'UltimateAdvancementAPI-2.8.1.jar',
    'EnthusiaTags.jar'
)
foreach ($name in $requiredChallengeArtifacts) {
    if (-not $challengeArtifacts.ContainsKey($name)) { throw "Challenge hash manifest is missing $name" }
    Check-Hash ("plugins\" + $name) $challengeArtifacts[$name]
}

$plugins = Join-Path $Root 'plugins'
foreach ($pattern in @('Chunky*.jar','LumaGuilds*.jar','EnthusiaTeleport*.jar')) {
    if (Get-ChildItem -LiteralPath $plugins -Filter $pattern -File -ErrorAction SilentlyContinue) { throw "Forbidden minimal-server plugin present: $pattern" }
}

Write-Host 'FRONTIER_TEST_RUNTIME_READY' -ForegroundColor Green
Write-Host 'Runtime files, workflow-pinned binary hashes, Secure Seed config, corrected borders, no-Elytra policy and challenge runtime all validate.'
Write-Host 'Still perform FIRST-BOOT-CHECKLIST.md live checks before admitting players.'
