#!/usr/bin/env bash
# Movie Omnibus launcher: builds the frontend bundle, starts the web server,
# and opens the browser once the server responds.
# Run with ./start-server.sh (or `bash start-server.sh`). Ctrl+C stops the server.

set -euo pipefail

cd "$(dirname "$0")"

APP_URL="http://localhost:8080"

open_url() {
    case "$(uname -s)" in
        Darwin)
            open "$1"
            ;;
        MINGW* | MSYS* | CYGWIN*)
            cmd.exe //c start "" "$1"
            ;;
        *)
            xdg-open "$1"
            ;;
    esac
}

wait_then_open_browser() {
    local attempt=0
    while [ "$attempt" -lt 120 ]; do
        if curl -fs -m 2 "$APP_URL/health" > /dev/null 2>&1; then
            open_url "$APP_URL" > /dev/null 2>&1 || true
            return 0
        fi
        attempt=$((attempt + 1))
        sleep 0.5
    done
}

echo
echo "[1/2] Building frontend..."
echo
if ! ./gradlew jsBrowserDevelopmentWebpack --console=plain; then
    echo
    echo "Frontend build failed. See the Gradle output above." >&2
    exit 1
fi

echo
echo "[2/2] Starting server on $APP_URL"
echo "Press Ctrl+C in this window to stop the server."
echo

wait_then_open_browser &
browser_pid=$!
trap 'kill "$browser_pid" 2> /dev/null || true' EXIT

if ! ./gradlew runServer --console=plain; then
    echo
    echo "The server exited with an error. See the Gradle output above." >&2
    echo "A common cause is PostgreSQL not running, or bad credentials in database.properties." >&2
    exit 1
fi
