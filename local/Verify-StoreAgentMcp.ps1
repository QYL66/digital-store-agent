param([int]$Port = 29099)
. (Join-Path $PSScriptRoot 'Local-Environment.ps1')
if (-not (Test-Path -LiteralPath $EnvFile) -or -not (Test-LocalPort $Port)) {
    throw 'Start the store agent MCP service before running this check.'
}
Initialize-LocalCredentials
$testUsers = @(1..2 | ForEach-Object { 'store-check-' + [guid]::NewGuid().ToString('N') })
try {
    & python (Join-Path $RepoDir 'scripts\verify_store_mcp.py') --port $Port --first $testUsers[0] --second $testUsers[1]
    if ($LASTEXITCODE -ne 0) { throw 'Store MCP checks failed.' }
} finally {
    $cleanup = "DELETE FROM store_demo_booking WHERE user_id IN ('$($testUsers[0])','$($testUsers[1])');"
    Invoke-LabDeskCompose exec -T postgres psql -X -U postgres -d store_agent -v ON_ERROR_STOP=1 -c $cleanup | Out-Null
}
