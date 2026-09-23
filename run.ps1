# Pixel Mosaic - Run Script (PowerShell)
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

Write-Host "===================================================" -ForegroundColor Cyan
Write-Host "         Starting Pixel Mosaic Application         " -ForegroundColor Cyan
Write-Host "===================================================" -ForegroundColor Cyan

$jarPath = Join-Path $ScriptDir "target\pixel-mosaic-1.0-SNAPSHOT.jar"
if (-not (Test-Path $jarPath)) {
    Write-Host "Building backend jar..." -ForegroundColor Yellow
    & .\mvnw.cmd package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Backend build failed."
        exit 1
    }
}

Write-Host "Starting backend (port 8080)..." -ForegroundColor Green
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$ScriptDir'; java -jar target\pixel-mosaic-1.0-SNAPSHOT.jar"

Write-Host "Starting frontend (port 5500)..." -ForegroundColor Green
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$ScriptDir'; python -m http.server 5500 -d frontend"

Start-Sleep -Seconds 3

Write-Host "Opening http://localhost:5500 in browser..." -ForegroundColor Cyan
Start-Process "http://localhost:5500"

Write-Host "Pixel Mosaic is ready and running!" -ForegroundColor Green
Write-Host "Frontend: http://localhost:5500" -ForegroundColor White
Write-Host "Backend:  http://localhost:8080" -ForegroundColor White
