# SchemaForge V4: reproducible source-package allowlist.
# Windows PowerShell 5.1 compatible. Does not mutate project or H2 data.
[CmdletBinding()]
param(
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path.TrimEnd('\', '/')
$projectName = Split-Path -Path $projectRoot -Leaf
$parentDir = Split-Path -Path $projectRoot -Parent
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputPath = Join-Path $parentDir ($projectName + '-source-clean-' + $timestamp + '.zip')
} elseif (-not [System.IO.Path]::IsPathRooted($OutputPath)) {
    $OutputPath = Join-Path $parentDir $OutputPath
}
$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)
$comparison = [System.StringComparison]::OrdinalIgnoreCase
if ($OutputPath.StartsWith($projectRoot + [System.IO.Path]::DirectorySeparatorChar, $comparison) -or
    $OutputPath.StartsWith($projectRoot + [System.IO.Path]::AltDirectorySeparatorChar, $comparison)) {
    throw 'Output ZIP must be outside the project root (to avoid bundling itself or other runtime files).'
}
if (Test-Path -LiteralPath $OutputPath) {
    throw "Refusing to overwrite existing package: $OutputPath"
}

# The source root contract is intentionally strict. Adding a root entry requires
# an explicit review; test exports and downloaded EA ZIPs are never source code.
$rootFiles = @('.gitignore', 'CHANGELOG.md', 'README.md', 'mvnw', 'mvnw.cmd', 'pom.xml')
$rootDirectories = @('.mvn', 'data', 'distribution', 'docs', 'scripts', 'src', 'ui')
$blockedParts = @('.git', '.idea', '.vscode', 'target', 'logs', 'tmp', 'temp', 'work',
    'output', 'outputs', 'generated', 'test-output', 'test-reports',
    'validation-output', 'uploads', 'downloads', '__pycache__', 'node_modules', 'dist', 'coverage')
$blockedNames = @('.DS_Store', 'Thumbs.db', 'Desktop.ini')

function Test-IncludeSource([string]$relativePath) {
    $normalized = $relativePath.Replace('\', '/')
    $parts = $normalized.Split('/')
    foreach ($part in $parts) {
        if ($blockedParts -contains $part) { return $false }
    }
    $fileName = $parts[$parts.Length - 1]
    if ($blockedNames -contains $fileName -or $fileName.StartsWith('~$')) { return $false }
    # Never package local UI secrets or private keystores, even under allowlisted ui/.
    if ($fileName -match '(?i)^(\.env($|\.)|application-local\.|application-.*\.local\.)' -or
        $fileName -match '(?i)\.(jks|p12|pfx|keystore)$') { return $false }
    if ($fileName -match '(?i)(\.log(\..*)?|\.mv\.db|\.trace\.db|\.lock\.db|\.tmp|\.bak|\.swp|\.class)$') {
        return $false
    }
    if ($parts[0] -eq 'data') { return $normalized -ceq 'data/README.md' }
    return $true
}

$files = New-Object 'System.Collections.Generic.List[System.IO.FileInfo]'
foreach ($name in $rootFiles) {
    $path = Join-Path $projectRoot $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing required root source file: $name" }
    $files.Add((Get-Item -LiteralPath $path -Force))
}
foreach ($directory in $rootDirectories) {
    $path = Join-Path $projectRoot $directory
    if (-not (Test-Path -LiteralPath $path -PathType Container)) { throw "Missing required source directory: $directory" }
    foreach ($file in (Get-ChildItem -LiteralPath $path -Recurse -Force -File -ErrorAction Stop)) {
        if (($file.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { continue }
        $relative = $file.FullName.Substring($projectRoot.Length).TrimStart('\', '/')
        if (Test-IncludeSource $relative) { $files.Add($file) }
    }
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($OutputPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in ($files | Sort-Object FullName)) {
        $relative = $file.FullName.Substring($projectRoot.Length).TrimStart('\', '/').Replace('\', '/')
        $entry = $projectName + '/' + $relative
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $file.FullName, $entry, [System.IO.Compression.CompressionLevel]::Optimal)
    }
} finally {
    $zip.Dispose()
}

$verificationZip = [System.IO.Compression.ZipFile]::OpenRead($OutputPath)
try {
    if ($verificationZip.Entries.Count -ne $files.Count) {
        throw 'Package entry-count mismatch; source ZIP verification failed.'
    }
    $entryNames = @($verificationZip.Entries | ForEach-Object { $_.FullName })
    if (($entryNames | Select-Object -Unique).Count -ne $entryNames.Count) {
        throw 'Duplicate ZIP entry names detected.'
    }
    foreach ($name in $rootFiles) {
        if (-not ($entryNames -ccontains ($projectName + '/' + $name))) {
            throw "Package is missing root file: $name"
        }
    }
    foreach ($entryName in $entryNames) {
        if (-not $entryName.StartsWith($projectName + '/', [System.StringComparison]::Ordinal)) {
            throw "Unexpected ZIP root: $entryName"
        }
        $relative = $entryName.Substring($projectName.Length + 1)
        $firstPart = $relative.Split('/')[0]
        if (($rootFiles -cnotcontains $relative) -and ($rootDirectories -cnotcontains $firstPart)) {
            throw "Unreviewed root entry: $entryName"
        }
        if (-not (Test-IncludeSource $relative)) {
            throw "Generated or local file leaked into source package: $entryName"
        }
    }
} finally {
    $verificationZip.Dispose()
}

Write-Host "Source ZIP: $OutputPath"
Write-Host "Entries: $($files.Count)"
Write-Host "SHA-256: $((Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash)"
