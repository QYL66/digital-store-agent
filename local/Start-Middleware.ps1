param([ValidateSet('Vector','Keyword','Hybrid')][string]$RetrievalMode = 'Vector')

. (Join-Path $PSScriptRoot 'Local-Environment.ps1')

Initialize-LocalCredentials
$services = @('postgres', 'redis', 'rustfs', 'rmqnamesrv', 'rmqbroker')
if ($RetrievalMode -ne 'Vector') {
    $ComposeOptions += @('-f', (Join-Path $SetupDir 'compose-retrieval.yaml'))
    $services += 'elasticsearch'
    if ($RetrievalMode -eq 'Hybrid') {
        if (-not $env:BAILIAN_API_KEY) { throw 'Hybrid mode requires BAILIAN_API_KEY.' }
        $services += @('neo4j', 'lightrag')
    }
}
Invoke-LabDeskCompose up -d --pull never --wait --wait-timeout 240 @services

$existingTopics = Invoke-LabDeskCompose exec -T rmqbroker sh mqadmin topicList -n rmqnamesrv:9876
foreach ($topic in @('knowledge-document-chunk_topic','knowledge-base-cleanup_topic','message-feedback_topic')) {
    if ($topic -notin $existingTopics) {
        $created = Invoke-LabDeskCompose exec -T rmqbroker sh mqadmin updateTopic -n rmqnamesrv:9876 -b 127.0.0.1:20911 -t $topic
        if (($created -join "`n") -notmatch 'create topic .* success') {
            throw "RocketMQ topic initialization failed for $topic."
        }
    }
}

$s3Ready = $false
for ($attempt = 0; $attempt -lt 35; $attempt++) {
    & curl.exe --silent --fail --max-time 3 --output NUL 'http://127.0.0.1:29000/health'
    if ($LASTEXITCODE -eq 0) { $s3Ready = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $s3Ready) { throw 'LabDesk RustFS is not ready.' }

$dbArgs = @('exec','-T','postgres','psql','-X','-U','postgres','-d','store_agent','-v','ON_ERROR_STOP=1')
$schemaExists = Invoke-LabDeskCompose @dbArgs -Atc "SELECT CASE WHEN to_regclass('public.t_user') IS NULL THEN 0 ELSE 1 END;"
if (($schemaExists | Select-Object -Last 1) -eq '0') {
    $sqlDir = Join-Path $RepoDir 'resources\database'
    Invoke-LabDeskCompose cp (Join-Path $sqlDir 'schema_pg.sql') 'postgres:/tmp/labdesk-schema.sql'
    Invoke-LabDeskCompose cp (Join-Path $sqlDir 'init_data_pg.sql') 'postgres:/tmp/labdesk-initial-data.sql'
    Invoke-LabDeskCompose @dbArgs --single-transaction -f /tmp/labdesk-schema.sql -f /tmp/labdesk-initial-data.sql | Out-Null
    Invoke-LabDeskCompose exec -T postgres rm /tmp/labdesk-schema.sql /tmp/labdesk-initial-data.sql
}
$files = @{
    'store-schema' = 'mcp-server\src\main\resources\db\store-agent.sql'
    'store-seed' = 'resources\database\store_agent_seed.sql'
    'memory-version' = 'resources\database\upgrades\store-agent\memory-version.sql'
}
foreach ($key in $files.Keys) {
    $remote = "/tmp/$key.sql"
    Invoke-LabDeskCompose cp (Join-Path $RepoDir $files[$key]) "postgres:$remote"
    Invoke-LabDeskCompose @dbArgs --single-transaction -f $remote | Out-Null
    Invoke-LabDeskCompose exec -T postgres rm $remote
}
& (Join-Path $SetupDir 'Import-StoreCatalog.ps1')
Invoke-LabDeskCompose @dbArgs -c "SELECT extversion AS pgvector_version FROM pg_extension WHERE extname='vector';"
