#!/bin/bash
# Makes LightHome the default HOME app by disabling the stock launcher.
#
# READ docs/SAFETY-AND-ROLLBACK.md FIRST. This script:
#   - Explains exactly what it's about to do and asks for interactive
#     confirmation before touching anything.
#   - Refuses to proceed if the system setup wizard appears to own your
#     currently-saved Wi-Fi network (disabling things in that state can
#     drop your Wi-Fi connection -- see the safety doc for the full
#     explanation and the Ethernet/rejoin workaround).
#   - Records the previously-default launcher package so
#     scripts/rollback.sh can restore it later.
#
# Usage:
#   TV_HOST=192.0.2.50 scripts/set-default-home.sh
#   STOCK_LAUNCHER_PACKAGE=com.some.other.launcher scripts/set-default-home.sh
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_FILE="$PROJECT_DIR/signing/previous-default-launcher.txt"
PACKAGE="com.lighthome.tv"
STOCK_LAUNCHER_PACKAGE="${STOCK_LAUNCHER_PACKAGE:-com.google.android.apps.tv.launcherx}"

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
echo " This will disable the stock launcher package:"
echo "   $STOCK_LAUNCHER_PACKAGE"
echo " so that LightHome ($PACKAGE) wins HOME resolution instead."
echo ""
echo " This is reversible (pm enable / scripts/rollback.sh brings the"
echo " stock launcher back exactly as it was) -- but see"
echo " docs/SAFETY-AND-ROLLBACK.md for the setup-wizard HOME-priority"
echo " finding: disabling the stock launcher alone may not be enough on"
echo " every device, and disabling the setup wizard on top of it WILL"
echo " drop your Wi-Fi connection if it owns your saved network (checked"
echo " below)."
echo "=================================================================="
read -r -p "Continue? [y/N] " CONFIRM
if [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "Y" ]; then
  echo "Aborted."
  exit 0
fi

echo "==> Checking whether the setup wizard owns your saved Wi-Fi network"
WIFI_DUMP="$("${ADB[@]}" shell dumpsys wifi 2>/dev/null || true)"
if echo "$WIFI_DUMP" | grep -q "cname=.*setupwizard"; then
  echo ""
  echo "REFUSING TO PROCEED: 'dumpsys wifi' shows a saved network owned"
  echo "(cname=) by a setup-wizard package. Disabling system packages in"
  echo "this state risks dropping your Wi-Fi connection -- see"
  echo "docs/SAFETY-AND-ROLLBACK.md ('The setup-wizard / Wi-Fi ownership"
  echo "trap') for the full explanation."
  echo ""
  echo "Workarounds:"
  echo "  - Switch the TV to a wired Ethernet connection, then re-run this"
  echo "    script (Wi-Fi ownership no longer matters once you're not"
  echo "    depending on that saved network)."
  echo "  - Or proceed anyway, accepting that you may need to manually"
  echo "    rejoin Wi-Fi afterwards -- have the Wi-Fi password ready, then"
  echo "    re-run with SKIP_WIFI_CHECK=1 set."
  echo ""
  if [ "${SKIP_WIFI_CHECK:-}" != "1" ]; then
    exit 1
  fi
  echo "SKIP_WIFI_CHECK=1 set -- proceeding anyway."
fi

echo "==> Recording the current default HOME package for rollback"
CURRENT_DEFAULT="$("${ADB[@]}" shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null \
    | tail -1 | cut -d/ -f1 | tr -d '\r')"
mkdir -p "$(dirname "$STATE_FILE")"
echo "${CURRENT_DEFAULT:-$STOCK_LAUNCHER_PACKAGE}" > "$STATE_FILE"
echo "    saved as: $(cat "$STATE_FILE") (-> $STATE_FILE, untracked)"

echo "==> Disabling $STOCK_LAUNCHER_PACKAGE"
"${ADB[@]}" shell pm disable-user --user 0 "$STOCK_LAUNCHER_PACKAGE"

echo ""
echo "Done. Press HOME on the remote -- LightHome should now come up"
echo "directly. If you instead see the setup wizard or a 'no launcher"
echo "found' prompt, see docs/SAFETY-AND-ROLLBACK.md's HOME-priority"
echo "section. To undo everything: scripts/rollback.sh"
