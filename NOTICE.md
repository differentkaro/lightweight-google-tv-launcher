# Notices and third-party attribution

## TMDB

This product uses the TMDB API but is not endorsed or certified by TMDB.

LightHome TV fetches title metadata and backdrop artwork from
[The Movie Database (TMDB)](https://www.themoviedb.org/) at runtime, using a
key you provide in `config.properties`. No TMDB artwork, data, or branding is
bundled with this repository.

## Material Symbols icons (Apache License 2.0)

Source: Google Material Symbols (Rounded, weight 400) and, for one icon not
present in Material Symbols, the legacy Material Icons set.
Repository: https://github.com/google/material-design-icons
License: Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0)

Fetched from:
```
https://raw.githubusercontent.com/google/material-design-icons/master/symbols/web/<name>/materialsymbolsrounded/<name>_24px.svg
```
and, for `settings_input_composite` only (no Material Symbols equivalent exists):
```
https://raw.githubusercontent.com/google/material-design-icons/master/src/action/settings_input_composite/materialiconsround/24px.svg
```

Icons included (`res/drawable/ic_*.xml`), converted from SVG path data to
Android VectorDrawable XML, verbatim path data, no redrawing:

| File | Material icon |
| --- | --- |
| `ic_settings.xml` | settings |
| `ic_tune.xml` | tune |
| `ic_input.xml` | input |
| `ic_login.xml` | login |
| `ic_settings_input_hdmi.xml` | settings_input_hdmi |
| `ic_settings_input_composite.xml` | settings_input_composite (legacy set) |
| `ic_live_tv.xml` | live_tv |
| `ic_play_arrow.xml` | play_arrow |
| `ic_keyboard_arrow_up.xml` | keyboard_arrow_up |
| `ic_visibility_off.xml` | visibility_off |
| `ic_refresh.xml` | refresh |
| `ic_open_with.xml` | open_with |
| `ic_info.xml` | info |
| `ic_delete.xml` | delete |

No modifications were made to the icon artwork beyond coordinate-system
normalization required for Android's VectorDrawable format (Material Symbols
SVGs use `viewBox="0 -960 960 960"`; each path was wrapped in a
`<group android:translateY="960">` to map into VectorDrawable's
`viewportWidth`/`viewportHeight="960 960"`).

## Trademarks

LightHome TV is not affiliated with, endorsed by, or sponsored by TCL, Google,
Netflix, Amazon, Apple, or TMDB. All product names, logos, and brands referenced
in this project (in code, configuration examples, or documentation) are property
of their respective owners and are used only to identify the apps a user may
choose to launch from their own TV. No third-party logos or TMDB artwork are
bundled with this repository; app tile banners are read from apps already
installed on the user's own device at runtime.
