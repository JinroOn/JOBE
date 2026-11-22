param(
    [Parameter(Mandatory = $true)]
    [string]$MajorName,

    [string]$DatasetVersion = "",

    [int]$TopK = 5,

    [double]$ScoreThreshold = 0.3
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$aiServiceDir = Resolve-Path (Join-Path $scriptDir "..")
$python = Join-Path $aiServiceDir ".venv\Scripts\python.exe"

if (-not (Test-Path $python)) {
    throw "Python virtualenv not found: $python"
}

$env:EMBEDDING_PROVIDER = "openai-compatible"
$env:EMBEDDING_MODEL = "text-embedding-3-large"
$env:EMBEDDING_DIMENSION = "1536"
$env:EMBEDDING_OUTPUT_DIMENSIONS = "1536"
$env:AI_RAG_VECTOR_SEARCH_ENABLED = "true"

Push-Location $aiServiceDir
try {
    $argsList = @(
        "scripts\search_major_rag.py",
        "--major-name", $MajorName,
        "--top-k", $TopK,
        "--score-threshold", $ScoreThreshold
    )

    if ($DatasetVersion.Trim() -ne "") {
        $argsList += @("--dataset-version", $DatasetVersion)
    }

    & $python @argsList
}
finally {
    Pop-Location
}
