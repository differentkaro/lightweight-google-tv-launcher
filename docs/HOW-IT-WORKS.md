# How it works

## Architecture

`MainActivity` is a thin shell: lifecycle, broadcast registration, and key
dispatch only. Everything else lives in `HomeView`, a single custom `View`
that owns:

- **`BackdropLayer`** -- the blurred/dimmed background behind the hero area
  (see `BlurUtil`, `GlassRenderer`).
- **`ContentLayer`** -- hero text, provider eyebrow + priority chip, app
  grid tiles, the inputs pill, settings icon.
- **`FocusLayer`** -- the focus ring/highlight that follows D-PAD navigation.
- **`OverlayLayer`** -- modal-ish overlays (inputs panel, hidden-apps sheet,
  move mode, etc. -- see `OverlayType`).

Everything is drawn directly onto the `Canvas` passed to `onDraw`. There is
no XML layout inflation for the main screen, no `RecyclerView`, no
`ViewGroup` hierarchy beyond the single `HomeView` itself.

## Motion

One shared clock (`MotionClock`, driven by a single `Choreographer.
postFrameCallback` loop) advances every animated value in the app each
frame. Each animated value is a `Spring` -- a critically-damped second-order
spring, not a time-based tween -- so starting a new animation from a value
that's already mid-motion (e.g. re-focusing before the previous focus move
finished) blends smoothly instead of snapping. `Motion` holds the small set
of tuned stiffness/damping constants used throughout.

Nothing here uses `ObjectAnimator`, `ValueAnimator`, or XML-defined
animations; it's one clock, one spring model, applied everywhere.

## Networking: the `:sync` process

`HeroSyncService` runs in its own process (`android:process=":sync"` in the
manifest) and is the **only** place in the entire app that opens a network
socket. On launcher resume, and on a periodic ~6h `JobScheduler` job, it:

1. Reads `AppConfig` (generated from `config.properties` by `build.sh`) for
   the TMDB key, region, priority-country settings, and configured
   providers.
2. Fetches three candidate pools from TMDB (priority-country discover,
   global trending, and an unrestricted-origin fallback discover) and scores
   every candidate (`trendingRank` + `popularity` within its own pool +
   `recency`, plus a bonus for priority-country titles).
3. Picks the final 12, caps same-provider runs at 3 in a row, and (if
   `priority_min > 0`) enforces a "priority title in slide 1 or 2" rule.
4. Downloads each title's backdrop into `filesDir/hero/`, writes `hero.json`
   atomically (write to `.tmp`, then `renameTo`), deletes any now-unreferenced
   old backdrop files, and broadcasts `ACTION_HERO_UPDATED` to the main
   process.
5. Kills its own process (`Process.killProcess`) once every concurrent job
   has finished, so `:sync` never lingers between syncs.

The foreground UI process (`HeroRepository`) only ever reads the JSON file
this process already wrote; it never makes a network call itself.

A single-flight lock (`SYNC_LOCK` / `ACTIVE_JOBS`) prevents the one-shot
"refresh now" job and the periodic job from racing each other if
`JobScheduler` fires both close together -- without it, two concurrent
`runSync()` calls could race on the shared provider-lookup budget, and one
job's process-kill could kill the process out from under the other mid-sync.

## Tile cache

`AppRepository` renders each installed app's banner (or icon, as a fallback)
into one small bitmap per app, keeps a `~3MB` `LruCache` in memory, and
mirrors it to disk (`cacheDir/tiles`) keyed by `package + lastUpdateTime`. A
tile is only re-rendered when that key changes -- i.e. when the app itself
was actually updated -- not on every grid refresh or launcher resume.

## Memory

Measured on the one TV this has actually been tested on (TCL Google TV,
Android 11, 2 GB RAM), via `dumpsys meminfo com.lighthome.tv` after a fresh
launch and after extended idle running:

- **PSS total**: roughly 50-55 MB.
- **Private Dirty**: roughly **16 MB**.

PSS (proportional set size) includes a share of memory pages that are
*shared* with other processes (loaded `.so`/`.oat`/`.art` mmaps, for
instance) -- on a device where many processes map the same system libraries,
that shared portion inflates PSS without reflecting memory this app is
actually holding exclusively. **Private Dirty is the more honest number**
for "how much RAM would be freed if this process died": pages that belong
only to this process and have been modified, so they can't be dropped and
re-read from disk like a clean mmap could be. ~16 MB private-dirty is why
this was worth building for a 2 GB TV where the stock launcher plus a
background browser process can otherwise eat most of the available RAM.

These numbers are from one device and one build; expect them to vary on
other hardware/Android versions.
