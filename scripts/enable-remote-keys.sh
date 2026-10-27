#!/bin/bash
# Optional helper: forces RemoteKeyService into Android's
# enabled_accessibility_services setting, the same thing
# RemoteKeyServiceGuard already does automatically on every boot and app
# resume. Useful if you want to nudge it without restarting the app, or to
# confirm it's actually enabled.
#
# Does nothing useful unless config.properties has a non-empty remote_keys=
# (and you've rebuilt + reinstalled since setting it) -- see
# config.example.properties and docs/TCL-NOTES.md.
#
# Usage:
#   TV_HOST=192.0.2.50 scripts/enable-remote-keys.sh
set -euo pipefail

PACKAGE="com.lighthome.tv"
COMPONENT="$PACKAGE/$PACKAGE.RemoteKeyService"

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found on PATH." >&2
  exit 1
fi

if [ -n "${TV_HOST:-}" ] && ! [[ "$TV_HOST" =~ ^[A-Za-z0-9._:-]+$ ]]; then
  echo "ERROR: TV_HOST=\"$TV_HOST\" doesn't look like a hostname/IP (allowed: letters, digits, '.', '_', '-', ':')." >&2
  exit 1
fi

if [ -n "${TV_HOST:-}" ]; then
  adb connect "$TV_HOST:5555" >/dev/null || true
  ADB=(adb -s "$TV_HOST:5555")
else
  ADB=(adb)
fi

echo "=================================================================="
echo " This grants the app android.permission.WRITE_SECURE_SETTINGS and"
echo " enables RemoteKeyService, an accessibility service that can"
echo " intercept remote-control key events before other apps see them."
echo " Only proceed if you built this app yourself and trust it."
echo "=================================================================="
read -r -p "Continue? [y/N] " CONFIRM
if [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "Y" ]; then
  echo "Aborted."
  exit 0
fi

echo "==> Granting WRITE_SECURE_SETTINGS (required to edit this setting from outside the app)"
"${ADB[@]}" shell pm grant "$PACKAGE" android.permission.WRITE_SECURE_SETTINGS || true

CURRENT="$("${ADB[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [ "$CURRENT" = "null" ] || [ -z "$CURRENT" ]; then
  NEW="$COMPONENT"
else
  if echo "$CURRENT" | tr ':' '\n' | grep -qx "$COMPONENT"; then
    echo "==> $COMPONENT is already enabled"
    NEW="$CURRENT"
  else
    NEW="$CURRENT:$COMPONENT"
  fi
fi

echo "==> Setting enabled_accessibility_services"
"${ADB[@]}" shell settings put secure enabled_accessibility_services "$NEW"
"${ADB[@]}" shell settings put secure accessibility_enabled 1

echo ""
echo "Done. If this is the first time, relaunch LightHome (or reboot) so the"
echo "accessibility framework picks up the new binding."
