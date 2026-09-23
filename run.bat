@echo off
setlocal
echo ===================================================
echo           Starting Morphosaic Application
echo ===================================================

cd /d "%~dp0"

REM Check if target jar exists, build if not
set JAR_FILE=
for %%f in (target\*.jar) do (
    if not "%%~xf"=="" set JAR_FILE=%%f
)
if not defined JAR_FILE (
    echo Building backend jar...
    call mvnw.cmd package -DskipTests
    for %%f in (target\*.jar) do (
        if not "%%~xf"=="" set JAR_FILE=%%f
    )
)

echo Starting backend on http://localhost:8080 ...
start "Morphosaic - Backend" cmd /c "java -jar "%JAR_FILE%""

echo Starting frontend on http://localhost:5500 ...
start "Morphosaic - Frontend" cmd /c "python -m http.server 5500 -d frontend"

timeout /t 3 /nobreak >nul

echo Opening browser at http://localhost:5500 ...
start http://localhost:5500

echo ===================================================
echo Morphosaic is running!
echo Frontend: http://localhost:5500
echo Backend:  http://localhost:8080
echo ===================================================
