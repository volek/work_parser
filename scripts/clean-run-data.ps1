# =============================================================================
# BPM Parser - Очистка данных от предыдущего запуска
# =============================================================================
# Удаляет:
#   - сгенерированные сообщения (messages/)
#   - логи (локальная папка logs/ и Docker volume parser-logs)
#   - артефакты сборки (build/, .gradle/)
#
# Опционально (флаг -FullReset): останавливает контейнеры и удаляет ВСЕ тома,
#   включая Druid (postgres, zookeeper, druid-*), если использовался docker-compose.druid.yml
# =============================================================================

param(
    [switch]$FullReset  # С полным сбросом: docker down -v для обоих compose
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $ProjectRoot

Write-Host "BPM Parser: очистка данных предыдущего запуска" -ForegroundColor Cyan
Write-Host "Корень проекта: $ProjectRoot" -ForegroundColor Gray
Write-Host ""

# 1. Остановить контейнеры парсера и удалить его тома (parser-logs)
Write-Host "[1/5] Остановка контейнеров парсера и удаление томов..." -ForegroundColor Yellow
docker compose down -v 2>$null
if ($LASTEXITCODE -ne 0) { Write-Host "  (compose не запущен или ошибка - пропуск)" -ForegroundColor Gray }
else { Write-Host "  Готово" -ForegroundColor Green }

# 2. Удалить сгенерированные сообщения
$messagesPath = Join-Path $ProjectRoot "messages"
if (Test-Path $messagesPath) {
    Write-Host "[2/5] Удаление сгенерированных сообщений (messages/)..." -ForegroundColor Yellow
    Get-ChildItem -Path $messagesPath -File | Remove-Item -Force
    $count = (Get-ChildItem -Path $messagesPath -File -ErrorAction SilentlyContinue).Count
    Write-Host "  Удалено файлов в messages/. Осталось: $count" -ForegroundColor Green
} else {
    Write-Host "[2/5] Папка messages/ отсутствует - пропуск" -ForegroundColor Gray
}

# 3. Удалить локальные логи (том parser-logs уже снят в шаге 1)
$logsPath = Join-Path $ProjectRoot "logs"
if (Test-Path $logsPath) {
    Write-Host "[3/5] Удаление локальных логов (logs/)..." -ForegroundColor Yellow
    Get-ChildItem -Path $logsPath -Recurse -File | Remove-Item -Force -ErrorAction SilentlyContinue
    Get-ChildItem -Path $logsPath -Directory | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "  Готово" -ForegroundColor Green
} else {
    Write-Host "[3/5] Папка logs/ отсутствует - пропуск" -ForegroundColor Gray
}

# 4. Очистка артефактов сборки
Write-Host "[4/5] Очистка артефактов сборки (build/, .gradle/)..." -ForegroundColor Yellow
$buildPath = Join-Path $ProjectRoot "build"
$gradlePath = Join-Path $ProjectRoot ".gradle"
if (Test-Path $buildPath) {
    Remove-Item -Path $buildPath -Recurse -Force
    Write-Host "  build/ удалён" -ForegroundColor Green
}
if (Test-Path $gradlePath) {
    Remove-Item -Path $gradlePath -Recurse -Force
    Write-Host "  .gradle/ удалён" -ForegroundColor Green
}
if (-not (Test-Path $buildPath) -and -not (Test-Path $gradlePath)) {
    Write-Host "  Нет артефактов сборки" -ForegroundColor Gray
}

# 5. Полный сброс (Druid + все тома)
if ($FullReset) {
    Write-Host "[5/5] Полный сброс: остановка Druid и удаление ВСЕХ томов..." -ForegroundColor Yellow
    docker compose -f docker-compose.druid.yml down -v 2>$null
    docker compose down -v 2>$null
    Write-Host "  Контейнеры и тома удалены" -ForegroundColor Green
} else {
    Write-Host "[5/5] Без полного сброса (Druid и тома не трогаем). Для сброса Druid запустите с -FullReset" -ForegroundColor Gray
}

Write-Host ""
Write-Host "Очистка завершена." -ForegroundColor Cyan
