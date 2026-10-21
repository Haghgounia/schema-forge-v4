# SchemaForge V4 root repair - Windows PowerShell 5.1 compatible.
# Moves only known generated files. Preserves H2 databases and all unknown files.
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Low')]
param(
    [string]$ProjectRoot
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Join-Path $PSScriptRoot '..'
}
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot 'pom.xml') -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $ProjectRoot 'src') -PathType Container)) {
    throw "Not a SchemaForge project root: $ProjectRoot (pom.xml or src/ missing)."
}

$items = @(
    @('ea-performance.log', 'logs\ea-performance\ea-performance.log'),
    @('ea-performance-offline.log', 'logs\ea-performance\ea-performance-offline.log'),
    @('ea-performance-no-spell.log', 'logs\ea-performance\ea-performance-no-spell.log'),
    @('ea-performance-offline-spelling.log', 'logs\ea-performance\ea-performance-offline-spelling.log'),
    @('ea-performance-summary.csv', 'logs\ea-performance\ea-performance-summary.csv'),
    @('v4145-auto.log', 'logs\glossary\v4145-auto.log'),
    @('v4145-dry-run.log', 'logs\glossary\v4145-dry-run.log'),
    @('glossary-acceptance.xlsx', 'test-output\glossary\glossary-acceptance.xlsx'),
    @('ea-perf-all.zip', 'test-output\ea\ea-perf-all.zip'),
    @('ea-perf-oracle.zip', 'test-output\ea\ea-perf-oracle.zip')
)

$planned = 0
$moved = 0
foreach ($pair in $items) {
    $source = Join-Path $ProjectRoot $pair[0]
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }

    $destination = Join-Path $ProjectRoot $pair[1]
    # Never overwrite an existing output: preserve both by choosing a unique name.
    if (Test-Path -LiteralPath $destination) {
        $dir = Split-Path -Parent $destination
        $base = [System.IO.Path]::GetFileNameWithoutExtension($destination)
        $ext = [System.IO.Path]::GetExtension($destination)
        $number = 1
        do {
            $destination = Join-Path $dir ('{0}-from-root-{1:D3}{2}' -f $base, $number, $ext)
            $number++
        } while (Test-Path -LiteralPath $destination)
    }

    $planned++
    if ($PSCmdlet.ShouldProcess($source, "Move to $destination")) {
        $parent = Split-Path -Parent $destination
        if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
            [void](New-Item -ItemType Directory -Path $parent -Force)
        }
        Move-Item -LiteralPath $source -Destination $destination -ErrorAction Stop
        if ((Test-Path -LiteralPath $source) -or -not (Test-Path -LiteralPath $destination -PathType Leaf)) {
            throw "Move verification failed: $source -> $destination"
        }
        Write-Host "[MOVED] $($pair[0]) -> $destination"
        $moved++
    }
}

Write-Host "Project root : $ProjectRoot"
Write-Host "Planned      : $planned"
Write-Host "Moved        : $moved"
if ($WhatIfPreference) {
    Write-Host '[PREVIEW ONLY] -WhatIf was used; no files were moved.'
} else {
    $remaining = @($items | Where-Object { Test-Path -LiteralPath (Join-Path $ProjectRoot $_[0]) -PathType Leaf })
    if ($remaining.Count -gt 0) {
        throw "Cleanup incomplete: $($remaining.Count) known root artifacts still exist."
    }
    Write-Host '[OK] No known generated artifacts remain as root files.'
}

$approved = @('.gitignore', 'CHANGELOG.md', 'README.md', 'mvnw', 'mvnw.cmd', 'pom.xml')
$otherFiles = @(Get-ChildItem -LiteralPath $ProjectRoot -File -Force | Where-Object { $approved -notcontains $_.Name } | ForEach-Object { $_.Name })
if ($otherFiles.Count -gt 0) {
    Write-Warning ('Other root files were left untouched for review: ' + ($otherFiles -join ', '))
}
if (Test-Path -LiteralPath (Join-Path $ProjectRoot 'schema-forge-v4') -PathType Container) {
    Write-Warning 'Nested schema-forge-v4/ detected. This may be from extracting the old DELTA inside the project root; review it manually before removing.'
}
Write-Host 'Safety: data/*.mv.db and .idea/ have not been touched.'
