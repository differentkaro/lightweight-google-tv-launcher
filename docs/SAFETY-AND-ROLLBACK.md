# Safety and rollback

Read this before running `scripts/set-default-home.sh`. Everything up to
that point (building, installing, previewing) is low-risk and fully
reversible by uninstalling the app.

## The setup-wizard / Wi-Fi ownership trap

On Android TV, the system setup wizard (the activity that runs on first
boot to walk through Wi-Fi, account sign-in, etc.) owns the saved Wi-Fi
network it created during that initial setup. If you disable the stock
launcher so your own HOME app wins the `CATEGORY_HOME` resolution, Android
TV has an additional wrinkle:

**HOME-priority finding:** when the stock launcher package is disabled, the
setup wizard's own recovery/first-run activity can outrank a third-party
launcher for `CATEGORY_HOME`, not just the stock launcher. In practice this
means disabling the stock launcher alone isn't enough -- you may also need
to disable the setup wizard package for your own launcher to actually win.

**The problem:** disabling the setup wizard package deletes the Wi-Fi
network *it* owns, because Android ties ownership of that saved network
configuration to the component that created it. You will lose your Wi-Fi
connection the moment you disable it, unless you:

- Rejoin the Wi-Fi network manually afterwards (you'll need the password
  handy), or
- Use a wired Ethernet connection instead, so the TV never depends on that
  Wi-Fi profile in the first place.

`scripts/set-default-home.sh` checks for this before doing anything:

```bash
adb shell dumpsys wifi | grep cname=
```

If a `cname=` (creator package name) entry shows the setup wizard as the
owner of your currently-saved network, the script **refuses to proceed** and
prints this explanation instead. Switch to Ethernet, or be ready to rejoin
Wi-Fi by hand, then re-run it.

## What NOT to disable

See [TCL-NOTES.md](TCL-NOTES.md) for the TCL-specific never-disable list.
In general: never disable the current default launcher without first
confirming your own launcher is installed and working, never disable
anything with "wifi", "bluetooth", "settings", "setupwizard", or
"inputmethod" in the package name without researching that specific
component first, and never disable anything you haven't verified is safe to
disable *on your specific model* -- debloat lists for one TCL/Android TV
model are not guaranteed to be safe on a different model or vendor.

## Reversible vs. not

- **Disabling** a package (`pm disable-user`) is reversible:
  `pm enable` brings it back exactly as it was, with no data loss.
- **Uninstalling** a system package is generally *not* reversible without a
  factory reset, and on some vendor builds not even then if the package was
  never part of the base recovery image.

`scripts/set-default-home.sh` and `scripts/install.sh` only ever disable (never
uninstall) other packages, and `scripts/set-default-home.sh` records the
previous default launcher's package name before changing it, specifically so
`scripts/rollback.sh` can restore it.

## Rollback

```bash
scripts/rollback.sh
```

Restores the previously-recorded default launcher and re-enables anything
`set-default-home.sh` disabled on your behalf. If you didn't use these
scripts and need to undo manually:

```bash
# Re-enable a package you disabled:
adb shell pm enable <package.name>

# Clear LightHome's HOME role / preferred-activity resolution for CATEGORY_HOME
# so Android re-prompts you for a default the next time you press HOME:
adb shell pm clear-package-preferred-activities com.lighthome.tv
adb shell cmd role remove-role-holder android.app.role.HOME com.lighthome.tv
```

## No-computer path (if ADB isn't available)

On the TV itself, without a computer:

Settings -> Apps -> See all apps -> **Show system apps** -> find the
package you disabled -> **Enable**.

The same screen (Settings -> Apps -> See all apps -> LightHome) also has an
**Uninstall** button if you want LightHome gone entirely and the stock
launcher back by default (uninstalling a non-system app like LightHome is
always safe and fully reversible by reinstalling it).

## Last resort

A factory reset (Settings -> System -> Reset -> Factory data reset, or the
hardware reset-button sequence documented for your model) always recovers a
TV from a broken launcher/Wi-Fi state, at the cost of erasing all other
device settings and accounts. Keep your Wi-Fi password and any account
credentials handy before you start down this path at all, so a factory
reset is a true last resort rather than a forced one.
