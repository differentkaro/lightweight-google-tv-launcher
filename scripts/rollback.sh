#!/bin/bash
# Reverses what scripts/set-default-home.sh did: re-enables the previously
# recorded default launcher. Does not uninstall LightHome -- do that
# separately (adb uninstall com.lighthome.tv, or Settings -> Apps on the TV)
# if you want it fully removed.
#
# Usage:
#   TV_HOST=192.0.2.50 scripts/rollback.sh
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_FILE="$PROJECT_DIR/signing/previous-default-launcher.txt"
PACKAGE="com.lighthome.tv"

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

if [ ! -f "$STATE_FILE" ]; then
  echo "No recorded previous launcher found at $STATE_FILE."
  echo "If you know the package name, re-enable it directly:"
  echo "  adb shell pm enable <package.name>"
  exit 1
fi

PREVIOUS="$(cat "$STATE_FILE")"
echo "==> Re-enabling $PREVIOUS"
"${ADB[@]}" shell pm enable --user 0 "$PREVIOUS"

echo "==> Clearing LightHome's preferred-activity / HOME role claim"
"${ADB[@]}" shell pm clear-package-preferred-activities "$PACKAGE" || true
"${ADB[@]}" shell cmd role remove-role-holder android.app.role.HOME "$PACKAGE" || true

echo ""
echo "Done. Press HOME -- you should get $PREVIOUS back (or a chooser if"
echo "more than one launcher is now enabled). LightHome itself is still"
echo "installed; uninstall it separately if you want it gone entirely."
