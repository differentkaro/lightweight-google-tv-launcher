#!/bin/bash
# Installs build/Home.apk to a TV over ADB and grants the one permission that
# can't be requested at runtime. Does NOT change your default launcher --
# see set-default-home.sh for that, separately and explicitly.
#
# Usage:
#   TV_HOST=192.0.2.50 scripts/install.sh
#
# TV_HOST is required -- no IP address is baked into this script. If you've
# already `adb connect`-ed and only have one device attached, you can instead
# run with TV_HOST unset and it will use whatever `adb` already targets.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$PROJECT_DIR/build/Home.apk"
PACKAGE="com.lighthome.tv"

if [ ! -f "$APK" ]; then
  echo "ERROR: $APK not found. Run ./build.sh first." >&2
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found on PATH. Install Android platform-tools." >&2
  exit 1
fi

if [ -n "${TV_HOST:-}" ] && ! [[ "$TV_HOST" =~ ^[A-Za-z0-9._:-]+$ ]]; then
  echo "ERROR: TV_HOST=\"$TV_HOST\" doesn't look like a hostname/IP (allowed: letters, digits, '.', '_', '-', ':')." >&2
  exit 1
fi

if [ -n "${TV_HOST:-}" ]; then
  echo "==> Connecting to $TV_HOST"
  adb connect "$TV_HOST:5555" >/dev/null || true
  ADB=(adb -s "$TV_HOST:5555")
else
  echo "==> TV_HOST not set; using whatever device adb already targets"
  DEVICE_COUNT="$(adb devices | grep -c -E 'device$' || true)"
  if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "ERROR: no adb device attached and TV_HOST not set." >&2
    echo "       export TV_HOST=<tv ip> and re-run, or 'adb connect' first." >&2
    exit 1
  fi
  ADB=(adb)
fi

echo "==> Installing $APK"
"${ADB[@]}" install -r "$APK"

echo "==> Granting WRITE_SECURE_SETTINGS (needed only for the remote-key remap feature)"
"${ADB[@]}" shell pm grant "$PACKAGE" android.permission.WRITE_SECURE_SETTINGS || \
  echo "    (grant failed -- harmless if you don't use remote_keys=)"

echo "==> TCL-specific: allowing APP_AUTO_START so the app survives a power cycle"
echo "    (see docs/TCL-NOTES.md -- this app-op may not exist on non-TCL builds,"
echo "    in which case the command below is expected to no-op or error harmlessly)"
"${ADB[@]}" shell appops set "$PACKAGE" APP_AUTO_START allow || \
  echo "    (APP_AUTO_START not available on this device -- expected on non-TCL builds)"

echo ""
echo "Installed. Launch LightHome from the app grid to preview it -- your"
echo "current default launcher is untouched. See docs/SETUP.md step 5 before"
echo "running set-default-home.sh."
