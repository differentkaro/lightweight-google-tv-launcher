# Contributing

This started as a personal project for one TV, so contributions that widen
device/firmware coverage are especially welcome -- but please read
[docs/TCL-NOTES.md](docs/TCL-NOTES.md) first: anything TCL-specific in this
repo is documented as findings from *one* model, not general advice.

## Before opening a PR

- Run `./build.sh` with an empty `tmdb_key` and confirm it still builds.
- Keep configuration changes in `config.properties` / `config.example.properties`,
  never hardcoded in `src/`.
- No TMDB artwork, third-party logos, or personal device data (IPs, serials,
  Wi-Fi credentials, screenshots of your own apps) in commits.
- If you're changing anything under `scripts/`, keep `set -euo pipefail`,
  no hardcoded IPs (use `$TV_HOST`), and comment anything device-specific.

## Reporting a bug

Please use the issue template -- it asks for your TV model, Android version,
and firmware build, since several behaviors in this app (especially the
remote-button remap) are confirmed on exactly one device and may not
generalize.

## Device reports

If you've tried this on a TV other than the one it was built for (TCL Google
TV, Android 11), a short report of what worked/didn't work is genuinely
useful, even without a code change attached.
