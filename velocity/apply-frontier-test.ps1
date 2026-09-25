[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$VelocityTomlPath,
    [Parameter(Mandatory = $true)][string]$VeloTabGroupsPath,
    [Parameter(Mandatory = $true)][string]$BackendHost,
    [int]$BackendPort = 25568
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Utf8 = [System.Text.UTF8Encoding]::new($false)

function Backup([string]$Path) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backup = "$Path.frontier-test.$stamp.bak"
    Copy-Item -LiteralPath $Path -Destination $backup -Force
    return $backup
}

if (-not (Test-Path -LiteralPath $VelocityTomlPath -PathType Leaf)) { throw "Missing velocity.toml: $VelocityTomlPath" }
if (-not (Test-Path -LiteralPath $VeloTabGroupsPath -PathType Leaf)) { throw "Missing VeloTAB groups file: $VeloTabGroupsPath" }
$velocityBackup = Backup $VelocityTomlPath
$tabBackup = Backup $VeloTabGroupsPath

try {
    $lines = [System.Collections.Generic.List[string]]::new()
    [System.IO.File]::ReadAllLines($VelocityTomlPath) | ForEach-Object { [void]$lines.Add($_) }
    for ($i = $lines.Count - 1; $i -ge 0; $i--) {
        if ($lines[$i] -match '^\s*(FRONTIER_TEST|FRONTIERTEST)\s*=') { $lines.RemoveAt($i) }
    }
    $servers = -1
    for ($i=0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^\[servers\]\s*$') { $servers=$i; break } }
    if ($servers -lt 0) { throw 'velocity.toml has no [servers] table.' }
    $insert = $servers + 1
    while ($insert -lt $lines.Count -and $lines[$insert] -notmatch '^\[') { $insert++ }
    $lines.Insert($insert, 'FRONTIER_TEST = "' + $BackendHost + ':' + $BackendPort + '"')
    [System.IO.File]::WriteAllLines($VelocityTomlPath, $lines.ToArray(), $Utf8)

    $velocityText = [System.IO.File]::ReadAllText($VelocityTomlPath)
    if ([regex]::Matches($velocityText, '(?m)^\s*FRONTIER_TEST\s*=').Count -ne 1) { throw 'Expected exactly one FRONTIER_TEST backend.' }
    foreach ($m in [regex]::Matches($velocityText, '(?m)^\s*try\s*=\s*\[(.*?)\]')) {
        if ($m.Groups[1].Value -match 'FRONTIER_TEST|FRONTIERTEST') { throw 'FRONTIER_TEST must not be in the fallback try list.' }
    }

    $groupSource = Join-Path $PSScriptRoot 'plugins\velocitab\frontier-test-group.yml'
    $groupBlock = [System.IO.File]::ReadAllText($groupSource).Split("`n",2)[1]
    $tabLines = [System.Collections.Generic.List[string]]::new()
    [System.IO.File]::ReadAllLines($VeloTabGroupsPath) | ForEach-Object { [void]$tabLines.Add($_) }
    $start = -1; $end = -1
    for ($i=0; $i -lt $tabLines.Count; $i++) {
        if ($tabLines[$i] -match '^- name:\s*FRONTIER_TEST\s*$') {
            $start=$i; $end=$tabLines.Count
            for ($j=$i+1; $j -lt $tabLines.Count; $j++) { if ($tabLines[$j] -match '^- name:\s*') { $end=$j; break } }
            break
        }
    }
    if ($start -ge 0) { for ($i=$end-1; $i -ge $start; $i--) { $tabLines.RemoveAt($i) } }
    if ($tabLines.Count -gt 0 -and -not [string]::IsNullOrWhiteSpace($tabLines[$tabLines.Count-1])) { [void]$tabLines.Add('') }
    ($groupBlock -split "`r?`n") | ForEach-Object { [void]$tabLines.Add($_) }
    [System.IO.File]::WriteAllLines($VeloTabGroupsPath, $tabLines.ToArray(), $Utf8)

    $tabText = [System.IO.File]::ReadAllText($VeloTabGroupsPath)
    if ([regex]::Matches($tabText, '(?m)^- name:\s*FRONTIER_TEST\s*$').Count -ne 1) { throw 'Expected exactly one FRONTIER_TEST VeloTAB group.' }
    if ($tabText -match '(?s)- name:\s*FRONTIER_TEST.*?%(lumaguilds|vault_eco|enthusiarep)_') { throw 'Frontier VeloTAB group contains a missing-plugin placeholder.' }
}
catch {
    Copy-Item -LiteralPath $velocityBackup -Destination $VelocityTomlPath -Force
    Copy-Item -LiteralPath $tabBackup -Destination $VeloTabGroupsPath -Force
    throw
}

Write-Host 'Velocity and VeloTAB updated for FRONTIER_TEST.' -ForegroundColor Green
Write-Host "Velocity backup: $velocityBackup"
Write-Host "VeloTAB backup: $tabBackup"
