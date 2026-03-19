<# =============================================================================
Быстрая полная очистка Druid: вычитать ВСЕ datasources и вычистить всё.

Druid удаляет данные в 2 шага:
1) markUnused (мягкое удаление: сегменты становятся "unused" и исчезают из запросов)
2) kill task (жёсткое удаление: удаление файлов сегментов из deep storage + чистка метаданных)

Скрипт делает для каждого datasource:
- Быстро получает minTime/maxTime через GET /druid/coordinator/v1/datasources?simple
- DELETE datasource (mark all segments unused)
- DELETE datasource?kill=true&interval=... (запускает kill task)
- Опционально ждёт завершения (флаг -Wait). По умолчанию НЕ ждёт, чтобы работало быстро.

Использование:
  $env:COORDINATOR_URL = "http://druid-host:8081"; .\scripts\clean-druid-remote.ps1
  .\scripts\clean-druid-remote.ps1 -CoordinatorUrl "http://druid-host:8081"
============================================================================= #>

param(
    [string]$CoordinatorUrl = $env:COORDINATOR_URL,
    [int]$HttpTimeoutSec = 60,
    [switch]$Wait,
    [int]$PollIntervalSec = 10,
    [int]$TimeoutSecPerDatasource = 1800,
    [int]$MaxKillRetries = 3
)

$ErrorActionPreference = "Stop"

if (-not $CoordinatorUrl) {
    Write-Host "Usage: `$env:COORDINATOR_URL = 'http://host:8081'; .\\scripts\\clean-druid-remote.ps1" -ForegroundColor Yellow
    Write-Host "   or: .\\scripts\\clean-druid-remote.ps1 -CoordinatorUrl 'http://host:8081'" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "COORDINATOR_URL must point to Druid Coordinator (default port 8081)." -ForegroundColor Gray
    exit 1
}

$CoordinatorUrl = $CoordinatorUrl.TrimEnd('/')

function Get-HttpStatusCode([object]$err) {
    try {
        if ($null -ne $err.Exception -and $null -ne $err.Exception.Response) {
            return [int]$err.Exception.Response.StatusCode
        }
    } catch { }
    return $null
}

function Get-Datasources([string]$baseUrl, [int]$timeoutSec) {
    $uri = "$baseUrl/druid/coordinator/v1/datasources"
    $result = Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec $timeoutSec
    if ($null -eq $result) { return @() }
    if ($result -is [string]) { return @($result) }
    return @($result)
}

function Get-DatasourcesSimple([string]$baseUrl, [int]$timeoutSec) {
    $uri = "$baseUrl/druid/coordinator/v1/datasources?simple"
    $result = Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec $timeoutSec
    if ($null -eq $result) { return @() }
    return @($result)
}

function Get-MetadataSegmentsFull([string]$baseUrl, [string]$ds, [int]$timeoutSec) {
    # Per docs: returns all segments for datasource as stored in metadata store.
    $uri = "$baseUrl/druid/coordinator/v1/metadata/datasources/$ds/segments?full"
    $result = Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec $timeoutSec
    if ($null -eq $result) { return @() }
    return @($result)
}

function To-IsoUtc([object]$value) {
    if ($null -eq $value) { return $null }
    try {
        $dto = [DateTimeOffset]::Parse([string]$value)
        return $dto.UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    } catch {
        return $null
    }
}

function Get-OverallIntervalFromSimple([object]$dsObj) {
    # Per API docs, simple response includes minTime/maxTime (often ISO strings)
    $minIso = To-IsoUtc $dsObj.minTime
    $maxIso = To-IsoUtc $dsObj.maxTime
    if ($minIso -and $maxIso) { return "$minIso/$maxIso" }
    return $null
}

function Get-OverallIntervalFromSegments([object[]]$segments) {
    $minStart = $null
    $maxEnd = $null

    foreach ($s in $segments) {
        $interval = $null
        if ($null -ne $s.interval) {
            $interval = [string]$s.interval
        } elseif ($null -ne $s.Segment -and $null -ne $s.Segment.interval) {
            $interval = [string]$s.Segment.interval
        }
        if (-not $interval) { continue }

        $parts = $interval.Split('/')
        if ($parts.Length -ne 2) { continue }

        try {
            $start = [DateTimeOffset]::Parse($parts[0])
            $end = [DateTimeOffset]::Parse($parts[1])
        } catch {
            continue
        }

        if ($null -eq $minStart -or $start -lt $minStart) { $minStart = $start }
        if ($null -eq $maxEnd -or $end -gt $maxEnd) { $maxEnd = $end }
    }

    if ($null -eq $minStart -or $null -eq $maxEnd) { return $null }
    return "$($minStart.UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ss.fffZ'))/$($maxEnd.UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ss.fffZ'))"
}

function Invoke-MarkUnused([string]$baseUrl, [string]$ds, [int]$timeoutSec) {
    $uri = "$baseUrl/druid/coordinator/v1/datasources/$ds"
    Invoke-WebRequest -Uri $uri -Method Delete -UseBasicParsing -TimeoutSec $timeoutSec | Out-Null
}

function Invoke-Kill([string]$baseUrl, [string]$ds, [string]$interval, [int]$timeoutSec) {
    $uri = "$baseUrl/druid/coordinator/v1/datasources/$ds?kill=true&interval=$([System.Uri]::EscapeDataString($interval))"
    Invoke-WebRequest -Uri $uri -Method Delete -UseBasicParsing -TimeoutSec $timeoutSec | Out-Null
}

function Wait-DatasourceGoneFast([string]$baseUrl, [string]$ds, [int]$timeoutSec, [int]$pollSec, [int]$httpTimeoutSec) {
    # Fast polling: datasource disappears from coordinator view (or segmentCount becomes 0).
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $obj = Invoke-RestMethod -Uri "$baseUrl/druid/coordinator/v1/datasources/$ds" -Method Get -TimeoutSec $httpTimeoutSec
            if ($null -eq $obj) { return $true }
            if ($null -ne $obj.segments) {
                # some versions expose segment count as `segments`
                if ([int]$obj.segments -eq 0) { return $true }
                Write-Host "    Still has segments: $($obj.segments)" -ForegroundColor DarkYellow
            } else {
                Write-Host "    Still present..." -ForegroundColor DarkYellow
            }
        } catch {
            $status = Get-HttpStatusCode $_
            if ($status -eq 404) { return $true }
            Write-Host "    Failed to poll status: $($_.Exception.Message)" -ForegroundColor DarkRed
        }
        Start-Sleep -Seconds $pollSec
    }
    return $false
}

function Wait-And-RetryKillIfNeeded([string]$baseUrl, [string]$ds, [int]$timeoutSec, [int]$pollSec, [int]$httpTimeoutSec, [int]$maxRetries) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    $retries = 0

    while ((Get-Date) -lt $deadline) {
        $gone = Wait-DatasourceGoneFast -baseUrl $baseUrl -ds $ds -timeoutSec $pollSec -pollSec $pollSec -httpTimeoutSec $httpTimeoutSec
        if ($gone) { return $true }

        # If still present, try to "aim" kill using metadata segments.
        if ($retries -lt $maxRetries) {
            $retries++
            Write-Host "  Retry kill ($retries/$maxRetries) using metadata segments..." -ForegroundColor DarkYellow
            try {
                $segments = Get-MetadataSegmentsFull -baseUrl $baseUrl -ds $ds -timeoutSec $httpTimeoutSec
                if ($segments.Count -eq 0) {
                    # Nothing in metadata -> should disappear soon
                    continue
                }
                $interval = Get-OverallIntervalFromSegments -segments $segments
                if (-not $interval) {
                    $interval = "1900-01-01T00:00:00.000Z/2100-01-01T00:00:00.000Z"
                }

                # Ensure unused again (safe, metadata-only) then kill.
                Invoke-MarkUnused -baseUrl $baseUrl -ds $ds -timeoutSec $httpTimeoutSec
                Invoke-Kill -baseUrl $baseUrl -ds $ds -interval $interval -timeoutSec $httpTimeoutSec
            } catch {
                Write-Host "  Retry kill failed: $($_.Exception.Message)" -ForegroundColor DarkRed
            }
        }
    }

    return $false
}

Write-Host "Druid Coordinator: $CoordinatorUrl" -ForegroundColor Cyan

try {
    $simple = Get-DatasourcesSimple -baseUrl $CoordinatorUrl -timeoutSec $HttpTimeoutSec
} catch {
    Write-Host "Failed to list datasources: $($_.Exception.Message)" -ForegroundColor Red
    exit 2
}

if ($simple.Count -eq 0) {
    Write-Host "No datasources found. Nothing to delete." -ForegroundColor Green
    exit 0
}

Write-Host "Datasources to delete ($($simple.Count)):" -ForegroundColor Gray
$simple | ForEach-Object { Write-Host "  - $($_.name)" -ForegroundColor Gray }
Write-Host ""

foreach ($dsObj in $simple) {
    $ds = [string]$dsObj.name
    Write-Host "Processing datasource: $ds" -ForegroundColor Cyan
    try {
        $overallInterval = Get-OverallIntervalFromSimple -dsObj $dsObj
        if (-not $overallInterval) {
            # Fallback: some setups may not expose intervals; use a very wide interval
            $overallInterval = "1900-01-01T00:00:00.000Z/2100-01-01T00:00:00.000Z"
            Write-Host "  Interval not derived; using fallback $overallInterval" -ForegroundColor DarkYellow
        } else {
            Write-Host "  Overall interval: $overallInterval" -ForegroundColor Gray
        }

        # Step 1: mark all segments as unused (soft delete)
        Invoke-MarkUnused -baseUrl $CoordinatorUrl -ds $ds -timeoutSec $HttpTimeoutSec
        Write-Host "  Marked segments as unused: $ds" -ForegroundColor Yellow

        # Step 2: kill unused segments in interval (hard delete)
        Invoke-Kill -baseUrl $CoordinatorUrl -ds $ds -interval $overallInterval -timeoutSec $HttpTimeoutSec
        Write-Host "  Kill requested: $ds" -ForegroundColor Yellow

        if ($Wait) {
            $ok = Wait-And-RetryKillIfNeeded -baseUrl $CoordinatorUrl -ds $ds -timeoutSec $TimeoutSecPerDatasource -pollSec $PollIntervalSec -httpTimeoutSec $HttpTimeoutSec -maxRetries $MaxKillRetries
            if ($ok) {
                Write-Host "  Cleaned: $ds" -ForegroundColor Green
            } else {
                Write-Host "  Timeout waiting cleanup: $ds" -ForegroundColor Red
            }
        } else {
            Write-Host "  (not waiting; use -Wait to poll completion)" -ForegroundColor DarkGray
        }
    } catch {
        $statusCode = Get-HttpStatusCode $_
        if ($statusCode -eq 404) {
            Write-Host "  Skip (not found): $ds" -ForegroundColor Gray
        } else {
            $statusText = if ($null -ne $statusCode) { "$statusCode" } else { "n/a" }
            Write-Host "  Error $statusText : $ds - $($_.Exception.Message)" -ForegroundColor Red
        }
    }
}

Write-Host ""
Write-Host "Done." -ForegroundColor Cyan
