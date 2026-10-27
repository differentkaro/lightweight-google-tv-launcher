# LightHome TV: a lightweight custom launcher for Google TV and Android TV

LightHome TV is a custom home screen replacement for Google TV and Android TV
devices, built specifically for low-RAM / slow Google TV and Android TV sets
where the stock launcher feels sluggish. It draws a hero area of currently
trending featured titles from [TMDB](https://www.themoviedb.org/), a
reorderable app grid, and an HDMI/USB inputs panel -- all hand-rolled, with no
WebView and no AndroidX in the app.

> **Status:** a personal project, built for and tested on exactly one TV.
> See [Risk and disclaimer](#risk-and-disclaimer) before you flash it onto
> anything you care about.

## Why

The stock Google TV / Android TV launcher is heavy: on a 2 GB-RAM TV it
competes for memory with whatever else is running, and every extra megabyte
of launcher overhead comes straight out of the budget available for actually
playing video. LightHome TV was built to be the opposite -- no WebView, no
AndroidX, no Gradle, plain Java with hand-rolled rendering -- and it shows:
on the tested TCL Google TV it holds around **16 MB of private memory**
(see [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md#memory) for what that number
means and how it was measured), leaving far more headroom for everything
else on a low-RAM set.

## What it does

- **Featured carousel** -- a hero area showing currently-trending titles
  pulled from [TMDB](https://www.themoviedb.org/), with an optional
  "priority country" boost (see [Configuration](#configuration)) if you want
  a local/regional catalogue mixed in alongside global trending content.
- **App grid** -- every launchable app on the device, with reorder (move
  tiles), hide/show, and uninstall, all from the launcher itself.
- **Inputs panel** -- a quick view of connected HDMI/USB inputs.
- **Optional remote-button remap** -- an opt-in accessibility service that
  can send specific remote-control buttons straight to an app of your choice,
  bypassing the TV firmware's own handler for that button. Fully dormant
  unless you configure at least one mapping.
- **Spring-based motion** -- every transition (focus moves, hero fades, the
  grid's reorder animation) is driven by one shared critically-damped spring
  clock, not property animators layered on top of each other.

## How it works, at a glance

- `HomeView` and its layers (`BackdropLayer`, `ContentLayer`, `FocusLayer`,
  `OverlayLayer`) do all drawing directly onto a `Canvas` -- there is no
  XML layout inflation for the main screen.
- A background `:sync` process (`HeroSyncService`) is the only place that
  ever opens a network socket. It runs on resume and on a periodic ~6h job,
  writes `hero.json` + downloaded backdrops to `filesDir/hero/`, then exits.
  The foreground UI process only ever reads that file.
- App tiles are rendered once per app (banner or icon, whichever the app
  provides) into a small bitmap cache, kept both in memory and on disk, and
  only re-rendered when the app's own `lastUpdateTime` changes.
- See [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md) for the full architecture,
  including the motion system and memory numbers from the one TV this has
  actually been measured on.

## Requirements

- An Android TV / Google TV device with Developer options and ADB enabled.
- A Mac or Linux machine with:
  - JDK 21 or 25
  - Android SDK command-line tools with `build-tools;36.1.0` and
    `platforms;android-34` installed (see
    [docs/SETUP.md](docs/SETUP.md) for why 36.x specifically, and why not
    Gradle)
- A free [TMDB](https://www.themoviedb.org/settings/api) API key/token, if you
  want the featured carousel to show real titles (it builds and runs fine
  without one -- you just get a calm "no key configured" fallback instead).

## Quick start

```bash
git clone <this repo> lighthome-tv
cd lighthome-tv
cp config.example.properties config.properties
# edit config.properties: at minimum set tmdb_key and region
./build.sh
# -> build/Home.apk
```

Then see [docs/SETUP.md](docs/SETUP.md) for installing it to a TV over ADB,
previewing it safely alongside the stock launcher, and (only once you're
happy with it) making it the default HOME app.

## Configuration

Everything that's specific to you -- your TMDB key, your region, which
streaming apps you use, any remote-button remap -- lives in
`config.properties` (copied from `config.example.properties`, which documents
every key with its default and an example). None of it is hardcoded, and
`config.properties` itself is git-ignored.

| Key | Default | What it does |
| --- | --- | --- |
| `tmdb_key` | *(empty)* | TMDB v4 read token or v3 key. Empty builds fine; featured area shows a fallback. |
| `region` | `US` | ISO-3166-1 country used as the watch-provider catalogue region. |
| `priority_country` | *(empty)* | Optional ISO code. Titles originating there get boosted in the featured mix. Empty = no boost, no chip. |
| `priority_label` | *(empty)* | Text for the small chip shown on priority-country titles (e.g. `Nollywood` for `NG`). Empty hides the chip. |
| `priority_min` | `0` | Minimum guaranteed priority-country slots among the 12 featured titles. |
| `priority_max_age_months` | `24` | How far back the priority pool's release-date window is allowed to widen if it's short on titles. |
| `window_months` | `12` | Base rolling release-date window for all pools. |
| `providers` | Netflix / Prime Video / Apple TV+ example | `name:tmdb_watch_provider_id(s):android_package` list, comma-separated. |
| `<name>_region` | *(falls back to `region`)* | Per-provider region override, e.g. `appletv_region=US`. |
| `remote_keys` | *(empty)* | `keycode:package` list remapping remote buttons. Empty = the remap feature stays fully off. |
| `default_hidden_packages` | stock AOSP camera/messaging | Packages hidden from the grid on first run. |

See `config.example.properties` for the full, commented version of all of
the above, including the TCL remote-button example codes.

## Tested on one TV only

This has been built and run against exactly one device: a **TCL Google TV,
Android 11, 2 GB RAM**. Other TVs, other Android TV skins, and other remotes
may behave differently -- especially the remote-button remap, which depends
entirely on TCL-specific key codes (see
[docs/TCL-NOTES.md](docs/TCL-NOTES.md)). Reports of it working (or not)
elsewhere are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md).

## Risk and disclaimer

Modifying what runs as your TV's home screen, disabling system apps, and
changing `Settings.Secure` values can leave your TV in a broken or
hard-to-recover state. **There is no warranty of any kind.** Read
[docs/SAFETY-AND-ROLLBACK.md](docs/SAFETY-AND-ROLLBACK.md) *before* making
this the default launcher, especially the section on the setup wizard owning
your Wi-Fi network.

LightHome TV is not affiliated with, endorsed by, or sponsored by TCL,
Google, Netflix, Amazon, Apple, or TMDB. All trademarks belong to their
respective owners. This product uses the TMDB API but is not endorsed or
certified by TMDB.

Screenshots are welcome in issues/PRs, but please don't include TMDB artwork
or other copyrighted material -- a placeholder image or a crop of your own
app grid is fine.

## License

[MIT](LICENSE). See [NOTICE.md](NOTICE.md) for third-party attribution
(Material Symbols icons, Apache-2.0).

---

**Keywords:** Google TV launcher, Android TV launcher, custom home screen,
low RAM, TCL Google TV, debloat, TMDB.
