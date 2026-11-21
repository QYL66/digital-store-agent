param([string]$Snapshot, [string]$DocumentsDirectory)
. (Join-Path $PSScriptRoot 'Local-Environment.ps1')
if (-not $Snapshot) { $Snapshot = Join-Path $RepoDir 'resources\store-catalog\records.json' }
$catalogTool = Join-Path $RepoDir 'scripts\store_catalog.py'
New-Item -ItemType Directory -Path $LogsDir -Force | Out-Null
$importId = [guid]::NewGuid().ToString('N')
$sql = Join-Path $LogsDir "store-catalog-$importId.sql"
$remote = "/tmp/store-catalog-$importId.sql"
$report = Join-Path $LogsDir 'catalog-coverage.json'
$effectivePath = Join-Path $LogsDir "store-effective-$importId.json"
$lock = Enter-StoreCatalogImportLock
try {
    $options = @($Snapshot, '--sql', $sql, '--report', $report)
    if ($DocumentsDirectory) { $options += '--emit-effective-snapshot' }
    & python $catalogTool @options
    if ($LASTEXITCODE -ne 0) { throw 'Catalogue validation failed.' }
    Initialize-LocalCredentials
    Invoke-LabDeskCompose cp $sql "postgres:$remote"
    try {
        $effective = Invoke-LabDeskCompose exec -T postgres psql -X -U postgres -d store_agent -v ON_ERROR_STOP=1 --single-transaction -Atq -f $remote
    } finally {
        Invoke-LabDeskCompose exec -T postgres rm -f $remote
    }
    if ($DocumentsDirectory) {
        [IO.File]::WriteAllBytes($effectivePath, [Convert]::FromBase64String([string]$effective))
        & python $catalogTool $Snapshot --docs $DocumentsDirectory --effective-snapshot $effectivePath | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Failed to prepare effective catalogue documents.' }
    }
} finally {
    try {
        foreach ($file in @($sql, $effectivePath)) {
            if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
        }
    } finally {
        $lock.ReleaseMutex()
        $lock.Dispose()
    }
}
Write-Host "Catalogue imported. Coverage: $report"
