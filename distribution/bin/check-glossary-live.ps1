# Read-only smoke check. Run after starting the app with SCHEMAFORGE_GLOSSARY_ENABLED=true.
param(
    [string]$BaseUrl = "http://localhost:9090",
    [int]$MinimumTerms = 804
)
$ErrorActionPreference = "Stop"
$base = $BaseUrl.TrimEnd('/')
try {
    $docs = Invoke-RestMethod -Uri "$base/v3/api-docs" -Method Get -TimeoutSec 20
    $requiredPaths = @(
        "/api/v1/glossary/terms",
        "/api/v1/glossary/review/queue",
        "/api/v1/glossary/review/import-report",
        "/api/v1/glossary/review/decision",
        "/api/v1/glossary/review/history"
    )
    foreach ($path in $requiredPaths) {
        if (-not ($docs.paths.PSObject.Properties.Name -contains $path)) {
            throw "OpenAPI path is missing: $path. Is SCHEMAFORGE_GLOSSARY_ENABLED=true set in the running process?"
        }
    }
    $all = Invoke-RestMethod -Uri "$base/api/v1/glossary/terms?page=0&size=1" -Method Get -TimeoutSec 30
    $phrases = Invoke-RestMethod -Uri "$base/api/v1/glossary/terms?kind=PHRASE&page=0&size=10" -Method Get -TimeoutSec 30
    $account = Invoke-RestMethod -Uri "$base/api/v1/glossary/terms?q=ACCOUNT&page=0&size=10" -Method Get -TimeoutSec 30
    if ([int64]$all.total -lt $MinimumTerms) {
        throw "Glossary contains $($all.total) visible terms; expected at least $MinimumTerms. If intentionally curated/deleted, rerun with -MinimumTerms 0."
    }
    if ([int64]$phrases.total -lt 4) {
        throw "Only $($phrases.total) of the four initial Atomic-sheet phrase entries are visible."
    }
    if ([int64]$account.total -lt 1) {
        throw "ACCOUNT cannot be found by Glossary REST search."
    }
    Write-Host "[PASS] Swagger/OpenAPI contains all glossary and review endpoints."
    Write-Host "[PASS] Visible glossary terms: $($all.total); initial phrase entries: $($phrases.total)."
    Write-Host "[PASS] ACCOUNT lookup succeeded; check is read-only."
    exit 0
} catch {
    Write-Error "[FAIL] Glossary live acceptance: $($_.Exception.Message)"
    exit 1
}
