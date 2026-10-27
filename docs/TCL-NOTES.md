# TCL-specific notes

These are findings from building and testing LightHome TV on **one specific
device: a TCL 50C645, sold as a 4K QLED Google TV.** The build identifies
itself as `ro.product.model` "Smart TV Pro", product `G03_4K_GB`, device
`BeyondTV4`, running Android 11 (SDK 30) on a Realtek RTD2880-family SoC with 2 GB
RAM, a 1080p UI at density 320, and the stock Google TV launcher
(`com.google.android.apps.tv.launcherx`). They are reported here because
they were genuinely useful during development, not as general advice for
other TCL models, other Android versions, or other vendors.
**This is not advice for your TV unless you've verified it independently.**

All personal/identifying details (device serial, Wi-Fi SSID/credentials,
IP/MAC addresses, account info) have been stripped from this document --
what remains is technical, device-model-level findings only.

## Packages observed safe to disable on the tested model

These were disabled with `pm disable-user --user 0 <package>` (not
uninstalled -- see [SAFETY-AND-ROLLBACK.md](SAFETY-AND-ROLLBACK.md) on the
difference, and reversible at any time with `pm enable`) during testing
without breaking core TV function on this specific model/firmware.

**Telemetry / setup helpers**
```
com.tcl.bi
com.tcl.useragreement
com.google.android.partnersetup
com.google.android.onetimeinitializer
com.google.android.feedback
```

**TCL promo / extras**
```
com.tcl.tv.tclhome_passive
com.tcl.channelplus
com.tcl.usercenter
com.tcl.hearaid
com.tcl.ocean.instructions
com.tcl.waterfall.overseas
com.tcl.browser
com.tcl.smartalexa
com.tcl.android.webview
com.google.android.syncadapters.calendar
```
`com.tcl.android.webview` is not the WebView implementation actually in
use -- check `dumpsys webviewupdate` before disabling any WebView package
to confirm which one is active (on the tested device it was
`com.google.android.webview`).

**TCL services**
```
com.tcl.esticker
com.tcl.messagebox
com.tcl.overseasappshow
com.tcl.partnercustomizer
com.tcl.keyhelp
com.tcl.logkit
com.tcl.dashboard
com.tcl.miracast
com.tcl.gallery
com.tcl.ui_mediaCenter
com.tcl.MultiScreenInteraction_TV
com.tcl.t_solo
com.tcl.guard
com.tcl.versionUpdateApp
```
Notes on a few of these: `com.tcl.miracast` is screen mirroring --
Chromecast built-in (`com.google.android.apps.mediashell`) is a separate
package and stays enabled. `com.tcl.ui_mediaCenter` is the USB media
player. `com.tcl.MultiScreenInteraction_TV` is TCL's "MagiConnect" phone
companion feature. `com.tcl.guard` is TCL's "Safety Guard" app.
`com.tcl.versionUpdateApp` is the firmware updater -- disabling it means
the TV receives no TCL firmware updates while it's off, which is a
trade-off to weigh, not a free win.

**Only when replacing the stock home screen**
```
com.google.android.apps.tv.launcherx
com.google.android.tungsten.setupwraith
```
See [SAFETY-AND-ROLLBACK.md](SAFETY-AND-ROLLBACK.md): on this build, the
setup wizard's recovery activity (`tungsten.setupwraith`) outranks
third-party launchers in HOME resolution once the stock launcher is
disabled, and it's also the component that owns the saved Wi-Fi network
association -- understand that doc before disabling either of these two.

**Already disabled out of the box on this unit** (listed for completeness,
not something this testing did):
```
com.google.android.videos
tv.wuaki.apptv
org.chromium.webview_shell
```

**Result after reboot:** on this TV, the disabled states persisted across a
reboot. One exception: one preinstalled streaming app re-enabled itself
after the first reboot, and stopped doing so only once
`com.tcl.partnercustomizer` was also disabled. Re-check disabled-state
persistence after the first reboot and again after any OTA update.

(See the **CleanYourTV** project linked at the bottom for a maintained,
community-sourced debloat list that covers a much wider range of
TCL/Android TV models than this single device's test notes can.)

## Never disable (on the tested model)

These were identified as core-system components that should **not** be
disabled, regardless of how unused they look in `pm list packages` -- doing
so breaks Wi-Fi, inputs, the remote, or the system itself:

```
com.tcl.providers.config
com.tcl.initsetup
com.tcl.factory.view
com.tcl.system.server
com.tcl.tcl_bt_rcu_service
com.tcl.autopair
com.tcl.tvinput
com.tcl.tv
com.tcl.settings
com.tcl.inputmethod.international
com.tcl.suspension
com.tcl.assistant
com.tcl.micmanager
com.google.android.katniss
com.google.android.apps.mediashell
```

Also never disable anything under `com.android.*`, any of
`com.google.android.gms` / `gsf` / `webview` / `vending`, or anything under
`com.realtek.*` / `com.mediatek.*`. Beyond this list, check any package
that requests the `android.permission.TV_INPUT_HARDWARE` permission before
touching it -- it may be part of the HDMI/input layer on this chipset.

`com.tcl.tv` in particular is also used directly by LightHome's Inputs
panel (`HomeView.isPackageInstalled("com.tcl.tv")` / launch intent) to show
and switch HDMI/USB input status -- disabling it breaks that panel in
addition to whatever else it's responsible for on this platform.
`com.tcl.suspension` is the input/quick-settings menu, `com.tcl.assistant`
and `com.tcl.micmanager` are tied to voice input alongside Google's own
`com.google.android.katniss`, and `com.google.android.apps.mediashell` is
the Chromecast built-in receiver -- keep all of these enabled even if they
look idle in `pm list packages`.

## Remote buttons and TCL-specific key codes

The remote used in testing is an RC902V-style Bluetooth remote. Its
dedicated app buttons send TCL-specific key codes that TCL's window policy
handles directly, rather than standard Android key codes:

| Button | Key code |
|---|---|
| Guard | 4140 |
| USB media | 4057 |
| "TCL Channel" / quick menu | 4020 |
| Netflix | 4062 |
| Prime Video | 4099 |
| YouTube | 4065 |

The Netflix/Prime Video/YouTube buttons work by sending a broadcast to a
TCL partner service. After disabling `com.tcl.partnercustomizer` and the
related packages above, those three buttons silently do nothing (the
broadcast receiver they target no longer exists), and Guard / USB media /
"TCL Channel" instead try to launch their now-disabled TCL app and fail.

An accessibility service declaring `flagRequestFilterKeyEvents` received
these physical key presses before TCL's own handler did, and could remap
them to launch other apps. Important caveat while testing this: **key
codes injected with `adb shell input keyevent <code>` did not reach the
accessibility service at all** -- they appear to go through a different,
lower-level path than a real physical button press takes. Test remaps with
actual physical remote presses, not ADB-injected key events. These codes
were observed on one remote and one firmware version; treat them as a
starting point for your own remote, not a universal mapping.

## Other findings

- **Background service/job starts are blocked by default for new apps.**
  This TCL firmware has a vendor auto-start app-op that blocks newly
  installed apps from starting background services/jobs until granted:
  ```bash
  adb shell cmd appops set <package> APP_AUTO_START allow
  ```
  This was needed for the launcher's own background sync job (TMDB data)
  to run reliably after install/reboot.
- **`cmd dreams` has no shell implementation on this build.** There's no
  daydream/screensaver service to talk to via that path. The screensaver
  can still be started/tested directly via:
  ```bash
  adb shell am start -n com.android.systemui/.Somnambulator
  ```
- **`cmd role get-role-holders` is not available on this Android 11
  build.** Use `dumpsys role` instead to inspect role holders.
- **The HOME role does not override HOME resolution by itself.** Neither
  `cmd role add-role-holder android.app.role.HOME <pkg>` nor
  `cmd package set-home-activity` changed which app actually launched on
  Home on this build -- HOME resolution followed ordinary manifest
  intent-filter priority instead.
- **`ro.adb.secure=1` requires accepting the RSA key prompt on the TV
  itself** the first time a given host connects -- there's no way around
  that from the host side.
- **`adb connect` from a macOS Homebrew-installed `adb` can fail** until
  the terminal/app running it is granted macOS's "Local Network" privacy
  permission. A pure-Python ADB client (e.g. CleanYourTV's `tools/tv.py`)
  sidesteps this, since it doesn't go through the same OS-level local
  network path.
- **Memory freed by disabling the stock launcher and setup wizard:** in one
  PSS snapshot on this TV, the stock launcher's two processes used
  roughly 120 MB combined, and the setup wizard roughly 110 MB; disabling
  both freed that memory for other apps. This is a single snapshot on one
  device, not a guaranteed number.

## How to find your own list

Don't copy the package names above onto a different model or firmware --
work out your own list instead:

```bash
adb shell pm list packages -f          # all packages, with their APK paths
adb shell pm list packages -d          # currently-disabled packages
adb shell pm list packages | grep -iE '<vendor>'   # filter by vendor prefix
```

Disable packages in small batches, not all at once, and after each batch
test: the Home screen, every HDMI input, launching a streaming app, and
Wi-Fi connectivity. If anything breaks, roll back immediately with
`pm enable --user 0 <package>` and narrow down which package in the batch
was responsible before continuing.

## Prior art

[CleanYourTV](https://github.com/Gathin23/cleanyourtv) is a maintained,
community-sourced debloat-list project covering a much wider range of
TCL/Android TV models than the single device tested here. If you're
researching what's safe to disable on your own TCL set, start there rather
than treating this document's short list as comprehensive.
