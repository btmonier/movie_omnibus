@echo off
rem Movie Omnibus launcher: builds the frontend bundle, starts the web server,
rem and opens the browser once the server responds.
rem Double-click this file, or run it from a terminal. Ctrl+C stops the server.

setlocal
cd /d "%~dp0"

set "APP_URL=http://localhost:8080"

echo.
echo [1/2] Building frontend...
echo.
call gradlew.bat jsBrowserDevelopmentWebpack --console=plain
if errorlevel 1 (
    echo.
    echo Frontend build failed. See the Gradle output above.
    echo.
    pause
    exit /b 1
)

echo.
echo [2/2] Starting server on %APP_URL%
echo Press Ctrl+C in this window to stop the server.
echo.

start "movie-omnibus-open-browser" /min powershell -NoProfile -ExecutionPolicy Bypass -Command "for($i=0;$i -lt 120;$i++){try{if((Invoke-WebRequest '%APP_URL%/health' -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200){Start-Process '%APP_URL%'; break}}catch{Start-Sleep -Milliseconds 500}}"

call gradlew.bat runServer --console=plain
if errorlevel 1 (
    echo.
    echo The server exited with an error. See the Gradle output above.
    echo A common cause is PostgreSQL not running, or bad credentials in database.properties.
    echo.
    pause
    exit /b 1
)

endlocal
