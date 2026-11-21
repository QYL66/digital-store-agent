$ErrorActionPreference = 'Stop'

function Test-Provider([string]$Provider, [string]$Url, [string]$Key, [string]$Body) {
    if (-not $Key) { throw "$Provider API key is missing." }
    $options = @{
        Uri = $Url
        Headers = @{ Authorization = "Bearer $Key" }
        TimeoutSec = 20
        UseBasicParsing = $true
        MaximumRedirection = 0
    }
    if ($Body) {
        $options.Method = 'Post'
        $options.ContentType = 'application/json'
        $options.Body = $Body
    }
    try {
        $response = Invoke-WebRequest @options
    } catch {
        $status = $_.Exception.Response.StatusCode
        if ($status) { throw "$Provider credential check returned HTTP $([int]$status)." }
        throw "$Provider credential check could not reach the configured endpoint."
    }
    return $response.Content | ConvertFrom-Json
}

$models = Test-Provider 'DeepSeek' 'https://api.deepseek.com/models' $env:DEEPSEEK_API_KEY
if ('deepseek-flash' -notin $models.data.id) { throw 'The DeepSeek endpoint did not list deepseek-flash.' }
$result = Test-Provider 'Bailian' 'https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings' $env:BAILIAN_API_KEY `
    '{"model":"text-embedding-v4","input":"connectivity check","dimensions":1536}'
if ($result.data[0].embedding.Count -ne 1536) { throw 'Bailian did not return the expected 1536-dimensional embedding.' }
Write-Host 'Verified DeepSeek model access and Bailian embeddings.'
