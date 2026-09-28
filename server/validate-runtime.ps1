[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$ManifestPath = Join-Path $Root 'BINARY-MANIFEST.yml'

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

Require-File 'BINARY-MANIFEST.yml' | Out-Null
$manifest = [System.IO.File]::ReadAllText($ManifestPath)
$runtimeMatch = [regex]::Match($manifest, '(?ms)^runtime:\s*\r?\n\s*java:\s*(\d+)\s*\r?\n\s*server:\s*\r?\n\s*file:\s*([^\r\n]+)\s*\r?\n\s*sha256:\s*([0-9a-f]{64})')
if (-not $runtimeMatch.Success) { throw 'Could not parse runtime block from BINARY-MANIFEST.yml.' }
$runtimeJava = [int]$runtimeMatch.Groups[1].Value
$runtimeFile = $runtimeMatch.Groups[2].Value.Trim()
$runtimeHash = $runtimeMatch.Groups[3].Value
if ($runtimeJava -ne 25 -or $runtimeFile -ne 'paper-26.3.jar') { throw "Expected Paper 26.3 / Java 25 runtime, found $runtimeFile / Java $runtimeJava" }
Check-Hash $runtimeFile $runtimeHash

foreach ($relative in @(
    'eula.txt',
    'server.properties',
    'bukkit.yml',
    'spigot.yml',
    'config\paper-global.yml',
    'config\paper-world-defaults.yml',
    'plugins\PlaceholderAPI\config.yml',
    'plugins\TAB\config.yml',
    'plugins\EnthusiaTempChallenges\config.yml',
    'plugins\EnthusiaTags\config.yml',
    'world\datapacks\enthusia-frontier-test\pack.mcmeta',
    'world\datapacks\enthusia-frontier-test\data\enthusia_test\function\load.mcfunction',
    'world\datapacks\enthusia-frontier-test\data\enthusia_test\function\tick.mcfunction',
    'world\datapacks\enthusia-frontier-test\data\minecraft\tags\function\load.json',
    'world\datapacks\enthusia-frontier-test\data\minecraft\tags\function\tick.json'
)) { Require-File $relative | Out-Null }

$properties = [System.IO.File]::ReadAllText((Join-Path $Root 'server.properties'))
if ($properties -notmatch '(?m)^online-mode=false\s*$') { throw 'Backend must remain offline-mode behind Velocity modern forwarding.' }
if ($properties -notmatch '(?m)^max-world-size=5000\s*$') { throw 'max-world-size must allow the full +/-5000 Overworld.' }
if ($properties -notmatch '(?m)^initial-enabled-packs=vanilla,file/enthusia-frontier-test\s*$') { throw 'Frontier bootstrap datapack must be explicitly enabled for first world creation.' }
if ($properties -notmatch '(?m)^level-seed=\s*$') { throw 'TEMP template must use a fresh random world seed.' }
if ($properties -match '(?m)^feature-level-seed=') { throw 'Legacy Leaf Secure Seed property remains in server.properties.' }

$borderFunction = [System.IO.File]::ReadAllText((Join-Path $Root 'world\datapacks\enthusia-frontier-test\data\enthusia_test\function\load.mcfunction'))
if ($borderFunction -notmatch 'execute in minecraft:overworld run worldborder set 10000(?:\s|$)') { throw 'Overworld border must be 10000 wide (+/-5000).' }
if ($borderFunction -notmatch 'execute in minecraft:the_nether run worldborder set 5000(?:\s|$)') { throw 'Nether border must be 5000 wide (+/-2500).' }
if ($borderFunction -notmatch 'execute in minecraft:the_end run worldborder set 1000(?:\s|$)') { throw 'End border must be 1000 wide (+/-500).' }

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

$pluginMatches = [regex]::Matches($manifest, '(?m)^  ([A-Za-z0-9_.-]+\.jar):\s*([0-9a-f]{64})\s*$')
if ($pluginMatches.Count -lt 1) { throw 'No deployable plugin hashes found in BINARY-MANIFEST.yml.' }
foreach ($match in $pluginMatches) { Check-Hash (Join-Path 'plugins' $match.Groups[1].Value) $match.Groups[2].Value }

$challengeHashFile = Require-File 'PLUGIN-SHA256SUMS.txt'
$challengeHashes = @{}
foreach ($line in [System.IO.File]::ReadAllLines($challengeHashFile)) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    if ($line -notmatch '^([0-9a-fA-F]{64})\s+(.+)$') { throw "Malformed challenge hash line: $line" }
    $challengeHashes[[System.IO.Path]::GetFileName($Matches[2].Trim())] = $Matches[1].ToLowerInvariant()
}
foreach ($name in @('EnthusiaTempChallenges-0.2.0-frontier.1.jar','EnthusiaTags.jar')) {
    if (-not $challengeHashes.ContainsKey($name)) { throw "Challenge hash manifest is missing $name" }
    Check-Hash (Join-Path 'plugins' $name) $challengeHashes[$name]
}

$plugins = Join-Path $Root 'plugins'
foreach ($pattern in @('Chunky*.jar','LumaGuilds*.jar','EnthusiaTeleport*.jar','EnthusiaAdvancements*.jar','UltimateAdvancementAPI*.jar')) {
    if (Get-ChildItem -LiteralPath $plugins -Filter $pattern -File -ErrorAction SilentlyContinue) { throw "Forbidden/unsupported minimal-server plugin present: $pattern" }
}
if (Test-Path -LiteralPath (Join-Path $Root 'leaf-1.21.11-179.jar')) { throw 'Legacy Leaf 1.21.11 runtime remains in the package.' }

Write-Host 'TEMP_RUNTIME_READY' -ForegroundColor Green
Write-Host 'Paper 26.3/Java 25 runtime, manifest-pinned plugins, borders, no-Elytra policy and challenge runtime all validate.'
Write-Host 'Still perform FIRST-BOOT-CHECKLIST.md live checks before admitting players.'
