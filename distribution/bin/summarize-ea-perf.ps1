param(
    [Parameter(Mandatory = $true)]
    [string]$LogPath,
    [string]$CsvPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $LogPath -PathType Leaf)) {
    throw "Log file not found: $LogPath"
}
if ([string]::IsNullOrWhiteSpace($CsvPath)) {
    $CsvPath = Join-Path (Split-Path -Parent (Resolve-Path -LiteralPath $LogPath)) 'ea-performance-summary.csv'
}

$measurements = New-Object 'System.Collections.Generic.List[object]'
foreach ($line in [System.IO.File]::ReadLines((Resolve-Path -LiteralPath $LogPath).Path)) {
    if ($line -notmatch 'EA_PERF section=(\w+)\s+(.*)') { continue }
    $fields = @{}
    $fields['section'] = $Matches[1]
    foreach ($match in [regex]::Matches($Matches[2], '([A-Za-z_]+)=([^\s]+)')) {
        $fields[$match.Groups[1].Value] = $match.Groups[2].Value
    }
    $record = [ordered]@{
        Section = $fields['section']
        GenerationId = $fields['generationId']
        File = $fields['file']
        Dbms = $fields['dbms']
    }
    foreach ($name in @('total_ms', 'parser_ms', 'validation_enrichment_ms', 'platforms_ms',
                       'metadata_ms', 'preload_ms', 'ddl_ms', 'comparison_ms', 'migration_ms',
                       'run_all_ms', 'crud_ms', 'diagrams_ms', 'canonical_json_ms',
                       'summary_ms', 'glossary_ms', 'manifest_ms', 'write_artifacts_ms',
                       'zip_ms', 'cleanup_ms', 'prepare_ms', 'generate_ms', 'total_generation_ms')) {
        $record[$name] = if ($fields.ContainsKey($name)) { $fields[$name] } else { $null }
    }
    $measurements.Add([pscustomobject]$record)
}

if ($measurements.Count -eq 0) {
    throw 'No EA_PERF measurements found. Set SCHEMAFORGE_EA_PERF_ENABLED=true before starting Spring Boot and repeat the request.'
}
$measurements | Export-Csv -LiteralPath $CsvPath -NoTypeInformation -Encoding UTF8
Write-Host "Measurements: $($measurements.Count)"
Write-Host "CSV: $CsvPath"
Write-Host 'Highest top-level times (milliseconds):'
$measurements |
    Where-Object { $_.Section -eq 'request' -or $_.Section -eq 'package' -or $_.Section -eq 'artifacts' -or $_.Section -eq 'prepare' } |
    Select-Object Section, GenerationId, total_ms, prepare_ms, generate_ms, platforms_ms, zip_ms |
    Format-Table -AutoSize
Write-Host 'Per-platform breakdown (milliseconds):'
$measurements |
    Where-Object { $_.Section -eq 'platform' } |
    Select-Object Dbms, total_ms, metadata_ms, preload_ms, ddl_ms, comparison_ms, migration_ms, run_all_ms |
    Format-Table -AutoSize
