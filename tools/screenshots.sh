#!/usr/bin/env bash
# Play Store screenshot rig — the Android analogue of the matron-apple
# marketing-screenshot setup.
#
#   tools/screenshots.sh [output-dir]     (default docs/store-assets/screenshots)
#
# Boots a fresh seeded matron-journal on 127.0.0.1:9810 (demo user + the
# marketing conversations from matron-journal's deploy/vps/demo/seed.mjs),
# keeps the demo agents connected via responder.mjs, boots the `matron-screens`
# AVD headless if no device is attached, bridges the server to the device with
# `adb reverse`, runs the MarketingScreenshots instrumented test, and pulls the
# PNGs into the output dir. Everything it started is torn down on exit.
#
# Requires: matron-journal checkout (MATRON_JOURNAL_PATH, default
# ~/Dev/matron-journal) with node_modules installed; node (MATRON_NODE_PATH to
# override); the emulator + `matron-screens` AVD (see docs/store-assets).
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-$REPO_DIR/docs/store-assets/screenshots}"
JOURNAL="${MATRON_JOURNAL_PATH:-$HOME/Dev/matron-journal}"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
ADB="$SDK/platform-tools/adb"
PORT=9810
PASSWORD="demo-screenshots"
AVD_NAME="matron-screens"
APP_ID="chat.matron.android"

[ -f "$JOURNAL/src/server.js" ] || { echo "matron-journal not found at $JOURNAL" >&2; exit 1; }
[ -d "$JOURNAL/node_modules" ] || { echo "run npm install in $JOURNAL first" >&2; exit 1; }
NODE="${MATRON_NODE_PATH:-$(command -v node || /bin/zsh -i -c 'command -v node')}"
[ -x "$NODE" ] || { echo "node not found — set MATRON_NODE_PATH" >&2; exit 1; }
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home}"

if nc -z 127.0.0.1 "$PORT" 2>/dev/null; then
  echo "port $PORT is already in use — stop whatever is on it first" >&2; exit 1
fi

RIG_DIR="$(mktemp -d /tmp/matron-screens.XXXXXX)"
SERVER_PID=""; RESPONDER_PID=""; EMULATOR_PID=""
cleanup() {
  [ -n "$RESPONDER_PID" ] && kill "$RESPONDER_PID" 2>/dev/null || true
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  if [ -n "$EMULATOR_PID" ]; then
    "$ADB" -s "$DEVICE" emu kill 2>/dev/null || kill "$EMULATOR_PID" 2>/dev/null || true
  fi
  rm -rf "$RIG_DIR"
}
trap cleanup EXIT

# ---- 1. Provision + boot the journal ----------------------------------------
export MATRON_DB="$RIG_DIR/matron.sqlite"
(cd "$JOURNAL" && "$NODE" bin/matron-admin.js user add demo --password "$PASSWORD")
AGENT1=$(cd "$JOURNAL" && "$NODE" bin/matron-admin.js agent add demo mac-studio | grep -o '[0-9a-f]\{64\}')
AGENT2=$(cd "$JOURNAL" && "$NODE" bin/matron-admin.js agent add demo homelab | grep -o '[0-9a-f]\{64\}')

(cd "$JOURNAL" && MATRON_PORT=$PORT MATRON_BIND=127.0.0.1 "$NODE" src/server.js > "$RIG_DIR/server.log" 2>&1) &
SERVER_PID=$!
for i in $(seq 1 50); do
  nc -z 127.0.0.1 "$PORT" 2>/dev/null && break
  kill -0 "$SERVER_PID" 2>/dev/null || { cat "$RIG_DIR/server.log" >&2; exit 1; }
  sleep 0.2
done
nc -z 127.0.0.1 "$PORT" || { echo "journal never became ready" >&2; cat "$RIG_DIR/server.log" >&2; exit 1; }

# ---- 2. Seed the marketing conversations -------------------------------------
CLIENT=$(curl -fsS -X POST "http://127.0.0.1:$PORT/login" -H 'content-type: application/json' \
  -d "{\"username\":\"demo\",\"password\":\"$PASSWORD\",\"device_name\":\"seeder\"}" \
  | "$NODE" -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>console.log(JSON.parse(d).token))')
(cd "$JOURNAL" && MATRON_DEMO_WS="ws://127.0.0.1:$PORT/ws" \
  MATRON_DEMO_AGENT_TOKEN="$AGENT1" MATRON_DEMO_CLIENT_TOKEN="$CLIENT" \
  "$NODE" deploy/vps/demo/seed.mjs)

# Responder keeps both agents CONNECTED (agent picker + recent_folders RPC).
(cd "$JOURNAL" && MATRON_DEMO_WS="ws://127.0.0.1:$PORT/ws" \
  MATRON_DEMO_AGENT_TOKEN="$AGENT1" MATRON_DEMO_AGENT2_TOKEN="$AGENT2" \
  "$NODE" deploy/vps/demo/responder.mjs > "$RIG_DIR/responder.log" 2>&1) &
RESPONDER_PID=$!

# ---- 3. Device: attached device or headless AVD -------------------------------
DEVICE=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
if [ -z "$DEVICE" ]; then
  echo "no device attached — booting $AVD_NAME headless"
  "$SDK/emulator/emulator" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim \
    -no-snapshot -gpu swiftshader_indirect > "$RIG_DIR/emulator.log" 2>&1 &
  EMULATOR_PID=$!
  "$ADB" wait-for-device
  DEVICE=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
  "$ADB" -s "$DEVICE" shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 1; done'
fi
echo "using device $DEVICE"

"$ADB" -s "$DEVICE" reverse "tcp:$PORT" "tcp:$PORT"

# Clean status bar (fixed clock, full battery, no notification icons).
"$ADB" -s "$DEVICE" shell settings put global sysui_demo_allowed 1
demo() { "$ADB" -s "$DEVICE" shell am broadcast -a com.android.systemui.demo -e command "$@" > /dev/null; }
demo enter
demo clock -e hhmm 0900
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4
demo notifications -e visible false

# ---- 4. Install + run the screenshot test ------------------------------------
(cd "$REPO_DIR" && ./gradlew :app:installDebug :app:installDebugAndroidTest)
"$ADB" -s "$DEVICE" shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS || true
"$ADB" -s "$DEVICE" shell pm clear "$APP_ID" > /dev/null   # fresh sign-in every run
"$ADB" -s "$DEVICE" shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS || true

"$ADB" -s "$DEVICE" shell am instrument -w \
  -e class chat.matron.android.marketing.MarketingScreenshots \
  "$APP_ID.test/androidx.test.runner.AndroidJUnitRunner"

# ---- 5. Pull the PNGs ---------------------------------------------------------
mkdir -p "$OUT_DIR"
"$ADB" -s "$DEVICE" pull "/sdcard/Android/data/$APP_ID/files/screenshots/." "$OUT_DIR"
demo exit
echo "screenshots in $OUT_DIR:"
ls -la "$OUT_DIR"
