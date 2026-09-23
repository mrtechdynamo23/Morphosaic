# Morphosaic - Run Script (PowerShell)
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

Write-Host "===================================================" -ForegroundColor Cyan
Write-Host "            Starting Morphosaic Application        " -ForegroundColor Cyan
Write-Host "===================================================" -ForegroundColor Cyan

$jar = Get-ChildItem -Path (Join-Path $ScriptDir "target") -Filter "*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
if (-not $jar) {
    Write-Host "Building backend jar..." -ForegroundColor Yellow
    & .\mvnw.cmd package -DskipTests
    $jar = Get-ChildItem -Path (Join-Path $ScriptDir "target") -Filter "*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
}
$jarPath = $jar.FullName

Write-Host "Starting backend (port 8080)..." -ForegroundColor Green
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$ScriptDir'; java -jar '$jarPath'"

Write-Host "Starting frontend (port 5500)..." -ForegroundColor Green
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$ScriptDir'; python -m http.server 5500 -d frontend"

Start-Sleep -Seconds 3

Write-Host "Opening http://localhost:5500 in browser..." -ForegroundColor Cyan
Start-Process "http://localhost:5500"

Write-Host "Morphosaic is ready and running!" -ForegroundColor Green
Write-Host "Frontend: http://localhost:5500" -ForegroundColor White
Write-Host "Backend:  http://localhost:8080" -ForegroundColor White
