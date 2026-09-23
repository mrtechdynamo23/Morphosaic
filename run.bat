@echo off
setlocal
echo ===================================================
echo           Starting Pixel Mosaic Application
echo ===================================================

cd /d "%~dp0"

REM Check if target jar exists, build if not
if not exist "target\pixel-mosaic-1.0-SNAPSHOT.jar" (
    echo Building backend jar...
    call mvnw.cmd package -DskipTests
    if errorlevel 1 (
        echo Failed to build backend.
        pause
        exit /b 1
    )
)

echo Starting backend on http://localhost:8080 ...
start "Pixel Mosaic - Backend" cmd /c "java -jar target\pixel-mosaic-1.0-SNAPSHOT.jar"

echo Starting frontend on http://localhost:5500 ...
start "Pixel Mosaic - Frontend" cmd /c "python -m http.server 5500 -d frontend"

timeout /t 3 /nobreak >nul

echo Opening browser at http://localhost:5500 ...
start http://localhost:5500

echo ===================================================
echo Pixel Mosaic is running!
echo Frontend: http://localhost:5500
echo Backend:  http://localhost:8080
echo ===================================================
