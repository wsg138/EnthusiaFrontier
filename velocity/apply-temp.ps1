[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$VelocityTomlPath,
    [Parameter(Mandatory = $true)][string]$VeloTabGroupsPath,
    [Parameter(Mandatory = $true)][string]$BackendHost,
    [Parameter(Mandatory = $true)][int]$BackendPort
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Utf8 = [System.Text.UTF8Encoding]::new($false)

function Backup-File([string]$Path) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backup = "$Path.temp.$stamp.bak"
    Copy-Item -LiteralPath $Path -Destination $backup -Force
    return $backup
}

if (-not (Test-Path -LiteralPath $VelocityTomlPath -PathType Leaf)) { throw "Missing velocity.toml: $VelocityTomlPath" }
if (-not (Test-Path -LiteralPath $VeloTabGroupsPath -PathType Leaf)) { throw "Missing VeloTAB groups file: $VeloTabGroupsPath" }

$velocityBackup = Backup-File $VelocityTomlPath
$tabBackup = Backup-File $VeloTabGroupsPath

try {
    $lines = [System.Collections.Generic.List[string]]::new()
    [System.IO.File]::ReadAllLines($VelocityTomlPath) | ForEach-Object { [void]$lines.Add($_) }

    for ($i = $lines.Count - 1; $i -ge 0; $i--) {
        if ($lines[$i] -match '^\s*(TEMP|FRONTIER_TEST|FRONTIERTEST)\s*=') { $lines.RemoveAt($i) }
    }

    $servers = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\[servers\]\s*$') { $servers = $i; break }
    }
    if ($servers -lt 0) { throw 'velocity.toml has no [servers] table.' }

    $insert = $servers + 1
    while ($insert -lt $lines.Count -and $lines[$insert] -notmatch '^\[') { $insert++ }
    $lines.Insert($insert, 'TEMP = "' + $BackendHost + ':' + $BackendPort + '"')
    [System.IO.File]::WriteAllLines($VelocityTomlPath, $lines.ToArray(), $Utf8)

    $velocityText = [System.IO.File]::ReadAllText($VelocityTomlPath)
    if ([regex]::Matches($velocityText, '(?m)^\s*TEMP\s*=').Count -ne 1) { throw 'Expected exactly one TEMP backend.' }
    foreach ($m in [regex]::Matches($velocityText, '(?m)^\s*try\s*=\s*\[(.*?)\]')) {
        if ($m.Groups[1].Value -match '(?i)["'']TEMP["'']') { throw 'TEMP must not be in the normal fallback try list.' }
    }

    $groupSource = Join-Path $PSScriptRoot 'plugins\velocitab\temp-group.yml'
    if (-not (Test-Path -LiteralPath $groupSource -PathType Leaf)) { throw "Missing TEMP VeloTAB template: $groupSource" }
    $groupSourceLines = [System.IO.File]::ReadAllLines($groupSource)
    $groupStart = -1
    for ($i = 0; $i -lt $groupSourceLines.Length; $i++) {
        if ($groupSourceLines[$i] -match '^- name:\s*TEMP\s*$') { $groupStart = $i; break }
    }
    if ($groupStart -lt 0) { throw 'TEMP VeloTAB template does not contain a TEMP group.' }
    $groupLines = $groupSourceLines[$groupStart..($groupSourceLines.Length - 1)]

    $tabLines = [System.Collections.Generic.List[string]]::new()
    [System.IO.File]::ReadAllLines($VeloTabGroupsPath) | ForEach-Object { [void]$tabLines.Add($_) }
    if (-not ($tabLines | Where-Object { $_ -match '^groups:\s*$' })) { throw 'VeloTAB groups file has no top-level groups: key.' }

    for ($i = $tabLines.Count - 1; $i -ge 0; $i--) {
        if ($tabLines[$i] -match '^- name:\s*(TEMP|FRONTIER_TEST)\s*$') {
            $start = $i
            $end = $tabLines.Count
            for ($j = $i + 1; $j -lt $tabLines.Count; $j++) {
                if ($tabLines[$j] -match '^- name:\s*') { $end = $j; break }
            }
            for ($j = $end - 1; $j -ge $start; $j--) { $tabLines.RemoveAt($j) }
        }
    }

    if ($tabLines.Count -gt 0 -and -not [string]::IsNullOrWhiteSpace($tabLines[$tabLines.Count - 1])) { [void]$tabLines.Add('') }
    $groupLines | ForEach-Object { [void]$tabLines.Add($_) }
    [System.IO.File]::WriteAllLines($VeloTabGroupsPath, $tabLines.ToArray(), $Utf8)

    $tabText = [System.IO.File]::ReadAllText($VeloTabGroupsPath)
    if ([regex]::Matches($tabText, '(?m)^- name:\s*TEMP\s*$').Count -ne 1) { throw 'Expected exactly one TEMP VeloTAB group.' }
}
catch {
    Copy-Item -LiteralPath $velocityBackup -Destination $VelocityTomlPath -Force
    Copy-Item -LiteralPath $tabBackup -Destination $VeloTabGroupsPath -Force
    throw
}

Write-Host 'Velocity and VeloTAB updated for /server TEMP.' -ForegroundColor Green
Write-Host "Velocity backup: $velocityBackup"
Write-Host "VeloTAB backup: $tabBackup"
