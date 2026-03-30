# Full pipeline: generate messages -> parse and ingest to Druid per strategy -> run all SQL queries.
# Run from project root (where docker-compose.yml is):
#   .\scripts\run-all-strategies.ps1
# Optional message count (default 500):
#   .\scripts\run-all-strategies.ps1 -MessageCount 100
# Optional warm limit for combined/compcom (10..1010, step 100); can pass multiple variants:
#   .\scripts\run-all-strategies.ps1 -WarmLimitVariants 10, 110, 210

param(
    [int] $MessageCount = 500,
    # Для стратегий combined и compcom: варианты лимита warm-колонок (10..1010, шаг 100).
    # Если не задано — один прогон без лимита (config по умолчанию).
    # Пример: -WarmLimitVariants 10, 110, 210 — три прогона с PARSER_WARM_VARIABLES_LIMIT=10, 110, 210.
    [int[]] $WarmLimitVariants = @()
)

$ErrorActionPreference = "Stop"
$root = (Get-Location).Path
if (-not (Test-Path (Join-Path $root "docker-compose.yml"))) {
    Write-Error "Run script from project root (where docker-compose.yml is). Current dir: $root"
}

$strategies = @("combined", "compcom", "eav", "hybrid", "default")
$strategiesWithWarm = @("combined", "compcom")
$logsDir = Join-Path $root "logs"
$outDir = Join-Path $root "query-results"
$messagesDir = Join-Path $root "messages"

# 0. Cleanup previous run artifacts: logs, query-results, messages, Druid datasources
Write-Host "=== Cleanup previous run: logs, query-results, messages, Druid datasources ===" -ForegroundColor Cyan

if (Test-Path $logsDir) {
    Get-ChildItem -Path $logsDir -Recurse -Force | Remove-Item -Recurse -Force
}
if (Test-Path $outDir) {
    Get-ChildItem -Path $outDir -Recurse -Force | Remove-Item -Recurse -Force
}
if (Test-Path $messagesDir) {
    Get-ChildItem -Path $messagesDir -Recurse -Force | Remove-Item -Recurse -Force
}

New-Item -ItemType Directory -Path $logsDir -Force | Out-Null
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
New-Item -ItemType Directory -Path $messagesDir -Force | Out-Null

# Validate query folders against manifest before pipeline run
Write-Host "=== Validate SQL manifest consistency ===" -ForegroundColor Cyan
$pythonCmd = if ($env:PYTHON_BIN) { $env:PYTHON_BIN } else { "python" }
$manifestCheckLog = Join-Path $logsDir "query_manifest_check.log"
$ErrorActionPreference = "Continue"
& $pythonCmd (Join-Path $root "scripts" "generate_queries.py") --check 2>&1 | Tee-Object -FilePath $manifestCheckLog
$ErrorActionPreference = "Stop"
if ($LASTEXITCODE -ne 0) {
    Write-Error "SQL manifest check failed (exit code $LASTEXITCODE). See log: $manifestCheckLog"
}

# Clean Druid datasources using existing helper. Coordinator URL is taken from env COORDINATOR_URL or DRUID_COORDINATOR_URL.
$coordinatorUrl = $env:COORDINATOR_URL
if (-not $coordinatorUrl -and $env:DRUID_COORDINATOR_URL) {
    $coordinatorUrl = $env:DRUID_COORDINATOR_URL
}
if ($coordinatorUrl) {
    Write-Host "Cleaning Druid datasources via Coordinator: $coordinatorUrl" -ForegroundColor Yellow
    & (Join-Path $root "scripts" "clean-druid-remote.ps1") -CoordinatorUrl $coordinatorUrl
} else {
    Write-Host "Skip Druid cleanup: COORDINATOR_URL / DRUID_COORDINATOR_URL is not set." -ForegroundColor Yellow
}

# 1. Generate test messages (inside container we use relative path 'messages')
Write-Host "=== Generate $MessageCount messages ===" -ForegroundColor Cyan
$ErrorActionPreference = "Continue"
docker compose run --rm bpm-parser generate messages $MessageCount 2>&1 | Tee-Object -FilePath (Join-Path $logsDir "generate.log")
$ErrorActionPreference = "Stop"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# 2. Parse and ingest to Druid per strategy
# Для combined и compcom при заданных WarmLimitVariants — по одному прогону на каждый вариант (логи *_ingest_warm{N}.log).
$ErrorActionPreference = "Continue"
foreach ($s in $strategies) {
    $variants = @($null)
    if ($s -in $strategiesWithWarm -and $WarmLimitVariants.Count -gt 0) {
        $variants = $WarmLimitVariants.ForEach({ $_ })
    }
    foreach ($warm in $variants) {
        $suffix = if ($null -eq $warm) { "" } else { "_warm$warm" }
        $ingestLog = Join-Path $logsDir "${s}_ingest${suffix}.log"
        Write-Host "=== Parse and ingest to Druid: $s$(if ($null -ne $warm) { " (warm limit $warm)" }) ===" -ForegroundColor Cyan
        $envArgs = @()
        if ($null -ne $warm) {
            $envArgs = @("-e", "PARSER_WARM_VARIABLES_LIMIT=$warm")
        }
        $dockerArgs = @("compose", "run", "--rm") + $envArgs + @("bpm-parser", "parse", $s, "messages", "--ingest")
        & docker @dockerArgs 2>&1 | Tee-Object -FilePath $ingestLog
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Ingest failed for strategy $s$(if ($null -ne $warm) { " warm=$warm" }) (exit code $LASTEXITCODE). Log: $ingestLog"
        }
    }
}
$ErrorActionPreference = "Stop"

# 3. Run all SQL queries per strategy
$ErrorActionPreference = "Continue"
foreach ($s in $strategies) {
    $queryPath = Join-Path (Join-Path $root "query") $s
    if (-not (Test-Path $queryPath)) {
        Write-Host "Skip queries for $s - directory not found: $queryPath" -ForegroundColor Yellow
        continue
    }

    $outFile = Join-Path $outDir "$s.txt"
    Set-Content -Path $outFile -Value "=== Strategy: $s ===`n" -Encoding UTF8

    $sqlFiles = Get-ChildItem -Path $queryPath -Recurse -Filter *.sql | Sort-Object FullName
    if ($sqlFiles.Count -eq 0) {
        Write-Host "No .sql files in $queryPath" -ForegroundColor Yellow
        continue
    }

    foreach ($f in $sqlFiles) {
        $rel = $f.FullName.Substring($root.Length).TrimStart('\', '/').Replace('\', '/')
        Add-Content -Path $outFile -Value "`n----- $rel -----" -Encoding UTF8
        $result = docker compose run --rm bpm-parser query $rel 2>&1
        Add-Content -Path $outFile -Value $result -Encoding UTF8
    }
    Write-Host "Done: $outFile" -ForegroundColor Green
}
$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "Pipeline done. Logs: $logsDir | Query results: $outDir" -ForegroundColor Green
