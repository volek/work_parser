# =============================================================================
# Удаление datasource'ов парсера в Druid на отдельном хосте
# =============================================================================
# Использование:
#   $env:COORDINATOR_URL = "http://druid-host:8081"; .\scripts\clean-druid-remote.ps1
#   .\scripts\clean-druid-remote.ps1 -CoordinatorUrl "http://druid-host:8081"
#
# Удаляются только datasource'ы, создаваемые BPM Parser:
#   Hybrid:   process_hybrid
#   EAV:      process_events, process_variables
#   Combined: process_main, process_variables_indexed
#
# Требуется URL Coordinator (порт 8081), не Router (8888).
# =============================================================================

param(
    [string]$CoordinatorUrl = $env:COORDINATOR_URL
)

$ErrorActionPreference = "Stop"

if (-not $CoordinatorUrl) {
    Write-Host "Usage: `$env:COORDINATOR_URL = 'http://host:8081'; .\scripts\clean-druid-remote.ps1" -ForegroundColor Yellow
    Write-Host "   or: .\scripts\clean-druid-remote.ps1 -CoordinatorUrl 'http://host:8081'" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "COORDINATOR_URL must point to Druid Coordinator (default port 8081)." -ForegroundColor Gray
    exit 1
}

$CoordinatorUrl = $CoordinatorUrl.TrimEnd('/')

$datasources = @(
    "process_hybrid",
    "process_events",
    "process_variables",
    "process_main",
    "process_variables_indexed"
)

Write-Host "Druid Coordinator: $CoordinatorUrl" -ForegroundColor Cyan
Write-Host "Datasources to delete: $($datasources -join ', ')" -ForegroundColor Gray
Write-Host ""

foreach ($ds in $datasources) {
    try {
        $response = Invoke-WebRequest -Uri "$CoordinatorUrl/druid/coordinator/v1/datasources/$ds" `
            -Method Delete -UseBasicParsing
        Write-Host "  Deleted: $ds" -ForegroundColor Green
    } catch {
        $statusCode = $_.Exception.Response.StatusCode
        if ($statusCode -eq [System.Net.HttpStatusCode]::NotFound) {
            Write-Host "  Skip (not found): $ds" -ForegroundColor Gray
        } else {
            Write-Host "  Error $statusCode : $ds - $($_.Exception.Message)" -ForegroundColor Red
        }
    }
}

Write-Host ""
Write-Host "Done. List datasources: curl -s $CoordinatorUrl/druid/coordinator/v1/datasources" -ForegroundColor Gray
