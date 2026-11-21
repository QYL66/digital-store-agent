$ErrorActionPreference = 'Stop'
$SetupDir = $PSScriptRoot
$RepoDir = Split-Path -Parent $SetupDir
$LogsDir = Join-Path $SetupDir 'logs'
$DockerExe = $env:STORE_AGENT_DOCKER_EXE
if (-not $DockerExe) {
    $dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
    $DockerExe = if ($dockerCommand) { $dockerCommand.Source } else {
        'D:\To_Code\DockerDesktop\resources\bin\docker.exe'
    }
}
$EnvFile = Join-Path $SetupDir '.env.local'
$ComposeOptions = @('--env-file', $EnvFile, '-f', (Join-Path $SetupDir 'compose.yaml'))

function Test-LocalPort([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        return $client.ConnectAsync('127.0.0.1', $Port).Wait(300) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Get-ManagedService([string]$Name) {
    $recordPath = Join-Path $LogsDir "$Name.process.json"
    if (-not (Test-Path -LiteralPath $recordPath)) { return }
    $record = Get-Content -Raw -LiteralPath $recordPath | ConvertFrom-Json
    $process = Get-Process -Id $record.id -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime().Ticks.ToString() -eq $record.started) {
        return $process
    }
}

function Invoke-LabDeskCompose {
    & $DockerExe compose @ComposeOptions @args
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed (exit $LASTEXITCODE)." }
}

function Enter-StoreCatalogImportLock {
    $mutex = [System.Threading.Mutex]::new($false, 'Global\DigitalStoreAgentCatalogImport')
    try {
        if (-not $mutex.WaitOne([TimeSpan]::FromMinutes(30))) {
            throw 'Another catalogue import is still running.'
        }
    } catch [System.Threading.AbandonedMutexException] {
        # An interrupted importer has released ownership to this process.
    } catch {
        $mutex.Dispose()
        throw
    }
    return $mutex
}

function Initialize-LocalCredentials {
    if (-not (Test-Path -LiteralPath $EnvFile)) {
        @(
            'STORE_AGENT_PG_PASSWORD=' + [guid]::NewGuid().ToString('N')
            'STORE_AGENT_REDIS_PASSWORD=' + [guid]::NewGuid().ToString('N')
            'STORE_AGENT_S3_KEY=store-agent-local'
            'STORE_AGENT_S3_SECRET=' + [guid]::NewGuid().ToString('N')
            'STORE_AGENT_NEO4J_PASSWORD=' + [guid]::NewGuid().ToString('N')
            'STORE_AGENT_MCP_TOKEN=' + [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
        ) | Set-Content -LiteralPath $EnvFile -Encoding ASCII
    }
    if (-not (Select-String -LiteralPath $EnvFile -Pattern '^STORE_AGENT_NEO4J_PASSWORD=' -Quiet)) {
        Add-Content -LiteralPath $EnvFile -Value ('STORE_AGENT_NEO4J_PASSWORD=' + [guid]::NewGuid().ToString('N')) -Encoding ASCII
    }
    $credentials = @{}
    foreach ($line in Get-Content -LiteralPath $EnvFile) {
        if ($line -match '^(STORE_AGENT_(?:PG_PASSWORD|REDIS_PASSWORD|S3_KEY|S3_SECRET)|STORE_AGENT_(?:MCP_TOKEN|NEO4J_PASSWORD))=(.+)$') {
            $credentials[$Matches[1]] = $Matches[2]
        }
    }
    foreach ($suffix in @('PG_PASSWORD','REDIS_PASSWORD','S3_KEY','S3_SECRET')) {
        $current = $credentials["STORE_AGENT_$suffix"]
        if (-not $current) { throw "Missing STORE_AGENT_$suffix in the local credentials file." }
        [Environment]::SetEnvironmentVariable("STORE_AGENT_$suffix", $current, 'Process')
    }
    if (-not $credentials['STORE_AGENT_MCP_TOKEN']) { throw 'Missing STORE_AGENT_MCP_TOKEN in the local credentials file.' }
    $env:STORE_AGENT_MCP_TOKEN = $credentials['STORE_AGENT_MCP_TOKEN']
    $env:STORE_AGENT_NEO4J_PASSWORD = $credentials['STORE_AGENT_NEO4J_PASSWORD']
}
