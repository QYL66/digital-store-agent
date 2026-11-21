param([string]$Username = 'admin', [string]$Snapshot, [System.Security.SecureString]$Password)
. (Join-Path $PSScriptRoot 'Local-Environment.ps1')

$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:29090/api/store-agent'

function Unwrap($result, [string]$action) {
    if ($result.code -ne '0') { throw "$action failed: $($result.message)" }
    return $result.data
}

function Call-Api([string]$method, [string]$path, $body = $null, [hashtable]$headers = @{}) {
    $options = @{ Uri = "$base$path"; Method = $method; Headers = $headers; TimeoutSec = 35 }
    if ($null -ne $body) {
        $options.ContentType = 'application/json; charset=utf-8'
        $options.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress -Depth 10))
    }
    $response = Invoke-WebRequest @options -UseBasicParsing
    $json = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    Unwrap ($json | ConvertFrom-Json) "$method $path"
}

function Upload-DemoDocument([System.IO.FileInfo]$file, [string]$kbId, [string]$token) {
    $client = [System.Net.Http.HttpClient]::new()
    $form = [System.Net.Http.MultipartFormDataContent]::new()
    try {
        $client.Timeout = [TimeSpan]::FromSeconds(35)
        $client.DefaultRequestHeaders.TryAddWithoutValidation('Authorization', $token) | Out-Null
        $content = [System.Net.Http.ByteArrayContent]::new([IO.File]::ReadAllBytes($file.FullName))
        $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('text/markdown')
        $digest = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.Substring(0, 16).ToLowerInvariant()
        $form.Add($content, 'file', ($file.BaseName + '.' + $digest + '.md'))
        $form.Add([System.Net.Http.StringContent]::new('file'), 'sourceType')
        $form.Add([System.Net.Http.StringContent]::new('chunk'), 'processMode')
        $response = $client.PostAsync("$base/knowledge-base/$kbId/docs/upload", $form).GetAwaiter().GetResult()
        try {
            $response.EnsureSuccessStatusCode() | Out-Null
            $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            Unwrap ($raw | ConvertFrom-Json) "Upload $($file.Name)"
        } finally { $response.Dispose() }
    } finally {
        $form.Dispose()
        $client.Dispose()
    }
}

Add-Type -AssemblyName System.Net.Http
$secure = if ($Password) { $Password } else { Read-Host 'Store agent administrator password' -AsSecureString }
$lock = $null
try {
    $lock = Enter-StoreCatalogImportLock
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $login = Call-Api 'Post' '/auth/login' @{ username = $Username; password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    }
    if ($login.role -ne 'admin' -or -not $login.token) { throw 'An administrator account is required.' }
    $headers = @{ Authorization = $login.token }
    $collection = 'store-agent-catalog'
    $name = 'StoreAgentCatalog'
    $page = Call-Api 'Get' ('/knowledge-base?size=100&name=' + [uri]::EscapeDataString($name)) $null $headers
    $kb = @($page.records | Where-Object { $_.collectionName -eq $collection } | Select-Object -First 1)
    $kbId = if ($kb.Count) { $kb[0].id } else {
        Call-Api 'Post' '/knowledge-base' @{ name = $name; collectionName = $collection; embeddingModel = 'qwen-emb-8b' } $headers
    }
    $demoDir = Join-Path $LogsDir ('catalog-documents-' + [guid]::NewGuid().ToString('N'))
    & (Join-Path $PSScriptRoot 'Import-StoreCatalog.ps1') -Snapshot $Snapshot -DocumentsDirectory $demoDir

    $records = @()
    for ($current = 1; ; $current++) {
        $page = Call-Api 'Get' "/knowledge-base/$kbId/docs?size=100&current=$current" $null $headers
        $records += @($page.records)
        if ($current -ge $page.pages -or @($page.records).Count -lt 100) { break }
    }
    $pending = @()
    $superseded = @()
    foreach ($file in Get-ChildItem -LiteralPath $demoDir -Filter 'store-*.md' -File) {
        $digest = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.Substring(0, 16).ToLowerInvariant()
        $expectedName = $file.BaseName + '.' + $digest + '.md'
        $prefix = '^' + [regex]::Escape($file.BaseName) + '\.[0-9a-f]{16}\.md$'
        $versions = @($records | Where-Object { $_.docName -cmatch $prefix })
        $sameContent = @($versions | Where-Object { $_.docName -ceq $expectedName })
        $doc = $sameContent | Where-Object { $_.status -eq 'success' -and $_.chunkCount -gt 0 } | Select-Object -First 1
        if (-not $doc) { $doc = $sameContent | Select-Object -First 1 }
        if (-not $doc) { $doc = Upload-DemoDocument $file $kbId $login.token }
        $superseded += @($versions | Where-Object { $_.id -ne $doc.id -and $_.status -ne 'running' })
        if ($doc.status -eq 'success' -and $doc.chunkCount -gt 0) {
            Write-Host "Already indexed: $expectedName"
            continue
        }
        if ($doc.status -ne 'running') {
            Call-Api 'Post' "/knowledge-base/docs/$($doc.id)/chunk" $null $headers | Out-Null
        }
        $pending += $doc.id
    }

    $deadline = (Get-Date).AddMinutes(20)
    while ($pending.Count -gt 0 -and (Get-Date) -lt $deadline) {
        $pending = @(foreach ($id in $pending) {
            $doc = Call-Api 'Get' "/knowledge-base/docs/$id" $null $headers
            if ($doc.status -eq 'failed') { throw "Indexing failed for document $id. See the document's chunking log." }
            if ($doc.status -eq 'success' -and $doc.chunkCount -gt 0) {
                Write-Host "Indexed: $($doc.docName) ($($doc.chunkCount) chunks)"
            } else { $id }
        })
        if ($pending.Count) { Start-Sleep -Seconds 2 }
    }
    if ($pending.Count) { throw 'Indexing is still running. Check the console or run this script again later.' }
    foreach ($old in $superseded) {
        Call-Api 'Delete' "/knowledge-base/docs/$($old.id)" $null $headers | Out-Null
    }
    Write-Host 'The store agent catalogue is indexed.'
} finally {
    if ($lock) {
        $lock.ReleaseMutex()
        $lock.Dispose()
    }
    if (-not $Password) { $secure.Dispose() }
}
