param([int]$Port = 29099)
. (Join-Path $PSScriptRoot 'Local-Environment.ps1')
if (-not (Test-Path -LiteralPath $EnvFile) -or -not (Test-LocalPort $Port)) {
    throw 'Start the store agent MCP service before running this check.'
}
Initialize-LocalCredentials
& python (Join-Path $RepoDir 'scripts\verify_store_mcp.py') --port $Port
if ($LASTEXITCODE -ne 0) { throw 'Store MCP checks failed.' }
