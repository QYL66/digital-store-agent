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
Invoke-StoreAgentCompose up -d --pull never --wait --wait-timeout 240 @services

$existingTopics = Invoke-StoreAgentCompose exec -T rmqbroker sh mqadmin topicList -n rmqnamesrv:9876
foreach ($topic in @('knowledge-document-chunk_topic','knowledge-base-cleanup_topic','message-feedback_topic')) {
    if ($topic -notin $existingTopics) {
        $created = Invoke-StoreAgentCompose exec -T rmqbroker sh mqadmin updateTopic -n rmqnamesrv:9876 -b 127.0.0.1:20911 -t $topic
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
if (-not $s3Ready) { throw 'Store Agent RustFS is not ready.' }

$dbArgs = @('exec','-T','postgres','psql','-X','-U','postgres','-d','store_agent','-v','ON_ERROR_STOP=1')
$schemaExists = Invoke-StoreAgentCompose @dbArgs -Atc "SELECT CASE WHEN to_regclass('public.t_user') IS NULL THEN 0 ELSE 1 END;"
if (($schemaExists | Select-Object -Last 1) -eq '0') {
    $sqlDir = Join-Path $RepoDir 'resources\database'
    Invoke-StoreAgentCompose cp (Join-Path $sqlDir 'schema_pg.sql') 'postgres:/tmp/store-agent-schema.sql'
    Invoke-StoreAgentCompose cp (Join-Path $sqlDir 'init_data_pg.sql') 'postgres:/tmp/store-agent-initial-data.sql'
    Invoke-StoreAgentCompose @dbArgs --single-transaction -f /tmp/store-agent-schema.sql -f /tmp/store-agent-initial-data.sql | Out-Null
    Invoke-StoreAgentCompose exec -T postgres rm /tmp/store-agent-schema.sql /tmp/store-agent-initial-data.sql
}
$files = @{
    'store-schema' = 'mcp-server\src\main\resources\db\store-agent.sql'
    'store-seed' = 'resources\database\store_agent_seed.sql'
}
foreach ($key in $files.Keys) {
    $remote = "/tmp/$key.sql"
    Invoke-StoreAgentCompose cp (Join-Path $RepoDir $files[$key]) "postgres:$remote"
    Invoke-StoreAgentCompose @dbArgs --single-transaction -f $remote | Out-Null
    Invoke-StoreAgentCompose exec -T postgres rm $remote
}
& (Join-Path $SetupDir 'Import-StoreCatalog.ps1')
Invoke-StoreAgentCompose @dbArgs -c "SELECT extversion AS pgvector_version FROM pg_extension WHERE extname='vector';"
