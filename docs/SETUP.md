# Setup

Step by step from a fresh TV to a running build. Read
[SAFETY-AND-ROLLBACK.md](SAFETY-AND-ROLLBACK.md) before the "make it default"
step at the end -- it's not required for building, installing, or previewing.

## 1. Enable Developer options and ADB on the TV

1. Settings -> System -> About -> click the build number ~7 times until
   "You are now a developer" appears.
2. Settings -> System -> Developer options -> turn on **USB debugging** (and,
   on some devices, **Network debugging** / **ADB debugging**).
3. Find the TV's IP address: Settings -> Network & Internet -> your
   connection -> IP address.

## 2. Connect ADB over the network

```bash
export TV_HOST=192.0.2.50   # replace with your TV's actual IP
adb connect "$TV_HOST:5555"
adb devices   # should list the TV as "device", not "unauthorized" or "offline"
```

Accept the pairing prompt that appears on the TV screen the first time.

**macOS Local Network permission:** macOS will often silently block `adb`'s
UDP/TCP discovery of devices on your LAN until you grant the Terminal (or
iTerm, or whichever app invokes `adb`) **Local Network** permission:
System Settings -> Privacy & Security -> Local Network. If `adb connect`
times out or `adb devices` shows nothing even though the TV is reachable by
`ping`, check that setting first.

**If Wi-Fi ADB is unreliable:** some TVs' Wi-Fi radios are flaky for ADB
specifically (fine for streaming, bad for a persistent debug connection).
A USB-to-Ethernet adapter on the TV, wired to your router, is far more
stable for the length of a development session than fighting Wi-Fi ADB --
and it sidesteps the Wi-Fi-ownership trap described in
[SAFETY-AND-ROLLBACK.md](SAFETY-AND-ROLLBACK.md) entirely, since you never
need to touch the saved Wi-Fi network at all.

## 3. Build

```bash
cp config.example.properties config.properties
# edit config.properties -- at minimum set tmdb_key and region
./build.sh
```

Output: `build/Home.apk`. See the README's Requirements section and
`build.sh`'s own error messages if a tool isn't found -- it tries to give you
the exact `sdkmanager` command to run.

## 4. Install and preview -- without replacing your home screen yet

```bash
scripts/install.sh
```

This installs the APK and grants the one permission that can't be requested
at runtime (`WRITE_SECURE_SETTINGS`, needed only if you use the remote-key
remap feature). It does **not** touch your default launcher.

To actually look at it without committing to anything: on the TV, press and
hold HOME (or go to Settings -> Apps -> See all apps -> LightHome -> Open),
or just launch it from the app grid like any other app. You can back out to
the real home screen at any time -- nothing is replaced yet.

## 5. Make it the default home app (optional, read the safety doc first)

```bash
scripts/set-default-home.sh
```

This script is interactive, explains what it's about to do, asks for
confirmation, and refuses to proceed if it detects the setup wizard still
owns your saved Wi-Fi network (see
[SAFETY-AND-ROLLBACK.md](SAFETY-AND-ROLLBACK.md) for exactly what that means
and how to work around it). It also records the previous default so
`scripts/rollback.sh` can restore it later.

## 6. Optional: remote-button remap

If you've filled in `remote_keys=` in `config.properties` and rebuilt:

```bash
scripts/enable-remote-keys.sh
```

This nudges the accessibility service into Android's
`enabled_accessibility_services` setting the same way `RemoteKeyServiceGuard`
does automatically on boot/resume -- useful if you want to force it without
restarting the app. With an empty `remote_keys=`, this feature does nothing
by design; see `config.example.properties` and
[TCL-NOTES.md](TCL-NOTES.md).
