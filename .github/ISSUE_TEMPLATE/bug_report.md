---
name: Bug report
about: Something isn't working right
title: ""
labels: bug
---

**TV model:**

**Android / Android TV version:**

**Firmware / build number:** (Settings -> System -> About)

**What happened:**

**What you expected:**

**Steps to reproduce:**

**config.properties (with `tmdb_key` removed/redacted):**

```
paste relevant config here, WITHOUT your tmdb_key
```

**Logs, if available:**

```
adb logcat -d | grep -E "HeroSync|RemoteKeyService|AndroidRuntime" 
```

**IMPORTANT before pasting logs or config above:** check the logcat output for `api_key=` or any token/`Authorization` value and redact it manually if you see one. Never paste your `config.properties` file itself (only the redacted snippet requested above) -- it contains your TMDB key.
