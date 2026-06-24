param(
    [Parameter(Mandatory = $true)]
    [string]$DatasetVersion,

    [string]$DatasetRoot = "..\datasets",

    [int]$Limit = 0,

    [switch]$DryRun,

    [switch]$Force
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
$env:EMBEDDING_BATCH_SIZE = "64"
$env:AI_RAG_VECTOR_SEARCH_ENABLED = "true"

Push-Location $aiServiceDir
try {
    & $python -m alembic upgrade head

    $argsList = @(
        "scripts\ingest_major_rag.py",
        "--dataset-version", $DatasetVersion,
        "--dataset-root", $DatasetRoot
    )

    if ($Limit -gt 0) {
        $argsList += @("--limit", $Limit)
    }
    if ($DryRun) {
        $argsList += "--dry-run"
    }
    if ($Force) {
        $argsList += "--force"
    }

    & $python @argsList
}
finally {
    Pop-Location
}
