#!/bin/bash
# Plain-Java / no-Gradle build for the LightHome TV launcher.
# aapt2 compile/link -> javac -> d8 -> zipalign -> apksigner.
#
# No Gradle, no AndroidX. This script auto-detects your JDK and Android SDK
# instead of hardcoding machine-specific paths, so it should work unmodified
# on any machine with the right tools installed. See docs/SETUP.md.
#
# Requirements (tested combination -- see README.md for why):
#   - A recent Android SDK command-line tools install with build-tools 36.x
#     and platforms;android-34 (`sdkmanager --install "build-tools;36.1.0"
#     "platforms;android-34"`)
#   - JDK 21 or 25. d8 from build-tools 34 crashes on class files produced by
#     JDK 25's javac; build-tools 36.x does not have that problem.
set -euo pipefail

# ------------------------------------------------------------------
# Toolchain discovery -- no hardcoded /opt/homebrew or /Library/Java paths.
# ------------------------------------------------------------------

# JAVA_HOME: respect an already-exported one, otherwise ask `java_home` (macOS)
# or fall back to resolving `javac` on PATH.
if [ -z "${JAVA_HOME:-}" ]; then
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
  fi
  if [ -z "${JAVA_HOME:-}" ] && command -v javac >/dev/null 2>&1; then
    JAVA_BIN="$(command -v javac)"
    JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
  fi
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
  echo "ERROR: could not find a JDK. Install JDK 21 or 25 and either put javac" >&2
  echo "       on PATH or export JAVA_HOME, then re-run." >&2
  exit 1
fi
export JAVA_HOME

# ANDROID_SDK_ROOT / ANDROID_HOME: respect either if already exported,
# otherwise try the common install locations.
if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  ANDROID_SDK_ROOT="${ANDROID_HOME:-}"
fi
if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  for candidate in \
      "$HOME/Library/Android/sdk" \
      "$HOME/Android/Sdk" \
      "/opt/homebrew/share/android-commandlinetools" \
      "/usr/local/share/android-commandlinetools"; do
    if [ -d "$candidate" ]; then
      ANDROID_SDK_ROOT="$candidate"
      break
    fi
  done
fi
if [ -z "${ANDROID_SDK_ROOT:-}" ] || [ ! -d "$ANDROID_SDK_ROOT" ]; then
  echo "ERROR: could not find an Android SDK. Install Android command-line" >&2
  echo "       tools and either export ANDROID_SDK_ROOT (or ANDROID_HOME) or" >&2
  echo "       install it at ~/Library/Android/sdk (macOS) / ~/Android/Sdk" >&2
  echo "       (Linux), then re-run." >&2
  exit 1
fi

# Pick the newest installed build-tools directory (expects >= 36.x; see the
# JDK 25 / d8 note above).
BUILD_TOOLS_DIR="$ANDROID_SDK_ROOT/build-tools"
if [ ! -d "$BUILD_TOOLS_DIR" ]; then
  echo "ERROR: no build-tools found under $BUILD_TOOLS_DIR" >&2
  echo "       Run: sdkmanager --install \"build-tools;36.1.0\"" >&2
  exit 1
fi
BUILD_TOOLS="$(find "$BUILD_TOOLS_DIR" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)"
if [ -z "$BUILD_TOOLS" ]; then
  echo "ERROR: $BUILD_TOOLS_DIR exists but contains no build-tools version" >&2
  exit 1
fi

PLATFORM="$ANDROID_SDK_ROOT/platforms/android-34"
ANDROID_JAR="$PLATFORM/android.jar"
if [ ! -f "$ANDROID_JAR" ]; then
  echo "ERROR: $ANDROID_JAR not found." >&2
  echo "       Run: sdkmanager --install \"platforms;android-34\"" >&2
  exit 1
fi

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
KEYTOOL="$JAVA_HOME/bin/keytool"
JAVAC="$JAVA_HOME/bin/javac"
for tool in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$KEYTOOL" "$JAVAC"; do
  if [ ! -x "$tool" ]; then
    echo "ERROR: required tool not found or not executable: $tool" >&2
    exit 1
  fi
done

echo "==> Using JAVA_HOME=$JAVA_HOME"
echo "==> Using ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT (build-tools $(basename "$BUILD_TOOLS"))"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

OUT="build"
GEN="$OUT/gen"
OBJ="$OUT/obj"
RES_COMPILED="$OUT/res-compiled"
SIGNING_DIR="$PROJECT_DIR/signing"
KEYSTORE="$SIGNING_DIR/lighthome.keystore"
KEYSTORE_PASS_FILE="$SIGNING_DIR/keystore.pass"

rm -rf "$RES_COMPILED" "$GEN" "$OBJ"
mkdir -p "$RES_COMPILED" "$GEN" "$OBJ" "$OUT"

# ------------------------------------------------------------------
# Read config.properties and generate build/gen/com/lighthome/tv/AppConfig.java.
# Nothing from config.properties (TMDB key, region, provider/remote-key mapping)
# ever sits in src/ or version control -- see .gitignore.
# ------------------------------------------------------------------

echo "==> Reading config.properties"

cfg_get() {
  # cfg_get <key> <default> -- first match wins, trailing \r stripped (CRLF-safe).
  local key="$1" default="$2" value=""
  if [ -f config.properties ]; then
    value="$(grep -E "^${key}=" config.properties | head -1 | cut -d= -f2- | tr -d '\r')"
  fi
  if [ -z "$value" ]; then echo "$default"; else echo "$value"; fi
}

TMDB_KEY="$(cfg_get tmdb_key "")"
REGION="$(cfg_get region "US")"
PRIORITY_COUNTRY="$(cfg_get priority_country "")"
PRIORITY_LABEL="$(cfg_get priority_label "")"
PRIORITY_MIN="$(cfg_get priority_min 0)"
PRIORITY_MAX_AGE_MONTHS="$(cfg_get priority_max_age_months 24)"
WINDOW_MONTHS="$(cfg_get window_months 12)"
PROVIDERS_RAW="$(cfg_get providers "")"
REMOTE_KEYS_RAW="$(cfg_get remote_keys "")"
DEFAULT_HIDDEN_RAW="$(cfg_get default_hidden_packages "")"

if [ -z "$TMDB_KEY" ]; then
  echo "    (no tmdb_key set -- hero will show the calm 'no key configured' fallback)"
fi
if [ -z "$PRIORITY_COUNTRY" ]; then
  echo "    (no priority_country set -- featured mix has no priority pool / chip)"
fi

# Escapes a value for safe interpolation inside a Java string literal
# (backslash and double-quote; config values are single-line so no
# newline handling is needed).
esc() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

if [ -n "$REGION" ] && ! [[ "$REGION" =~ ^[A-Za-z]{2}$ ]]; then
  echo "ERROR: region=\"$REGION\" must be exactly two letters (e.g. US, NG)." >&2
  exit 1
fi
if [ -n "$PRIORITY_COUNTRY" ] && ! [[ "$PRIORITY_COUNTRY" =~ ^[A-Za-z]{2}$ ]]; then
  echo "ERROR: priority_country=\"$PRIORITY_COUNTRY\" must be exactly two letters." >&2
  exit 1
fi
if [ -n "$PROVIDERS_RAW" ]; then
  IFS=',' read -ra VALIDATE_PROVIDER_ENTRIES <<< "$PROVIDERS_RAW"
  for entry in "${VALIDATE_PROVIDER_ENTRIES[@]}"; do
    v_name="$(echo "$entry" | cut -d: -f1)"
    v_ids="$(echo "$entry" | cut -d: -f2)"
    v_pkg="$(echo "$entry" | cut -d: -f3)"
    if ! [[ "$v_ids" =~ ^[0-9]+(\|[0-9]+)*$ ]]; then
      echo "ERROR: providers= entry \"$entry\" has a bad watch-provider id list \"$v_ids\" (expected digits separated by '|')." >&2
      exit 1
    fi
    if ! [[ "$v_pkg" =~ ^[A-Za-z0-9._]+$ ]]; then
      echo "ERROR: providers= entry \"$entry\" has a bad package name \"$v_pkg\" (expected ^[A-Za-z0-9._]+\$)." >&2
      exit 1
    fi
    v_name_region="$(cfg_get "${v_name}_region" "$REGION")"
    if [ -n "$v_name_region" ] && ! [[ "$v_name_region" =~ ^[A-Za-z]{2}$ ]]; then
      echo "ERROR: ${v_name}_region=\"$v_name_region\" must be exactly two letters." >&2
      exit 1
    fi
  done
fi
if [ -n "$REMOTE_KEYS_RAW" ]; then
  IFS=',' read -ra VALIDATE_RK_ENTRIES <<< "$REMOTE_KEYS_RAW"
  for entry in "${VALIDATE_RK_ENTRIES[@]}"; do
    v_code="$(echo "$entry" | cut -d: -f1 | tr -d '[:space:]')"
    v_pkg="$(echo "$entry" | cut -d: -f2 | tr -d '[:space:]')"
    if [ -z "$v_code" ] && [ -z "$v_pkg" ]; then
      continue
    fi
    if ! [[ "$v_code" =~ ^[0-9]+$ ]]; then
      echo "ERROR: remote_keys= entry \"$entry\" has a non-integer key code \"$v_code\"." >&2
      exit 1
    fi
    if ! [[ "$v_pkg" =~ ^[A-Za-z0-9._]+$ ]]; then
      echo "ERROR: remote_keys= entry \"$entry\" has a bad package name \"$v_pkg\" (expected ^[A-Za-z0-9._]+\$)." >&2
      exit 1
    fi
  done
fi
if [ -n "$DEFAULT_HIDDEN_RAW" ]; then
  IFS=',' read -ra VALIDATE_HIDDEN_ENTRIES <<< "$DEFAULT_HIDDEN_RAW"
  for pkg in "${VALIDATE_HIDDEN_ENTRIES[@]}"; do
    v_pkg_trimmed="$(echo "$pkg" | tr -d '[:space:]')"
    if [ -n "$v_pkg_trimmed" ] && ! [[ "$v_pkg_trimmed" =~ ^[A-Za-z0-9._]+$ ]]; then
      echo "ERROR: default_hidden_packages= entry \"$v_pkg_trimmed\" is a bad package name (expected ^[A-Za-z0-9._]+\$)." >&2
      exit 1
    fi
  done
fi

# Per-provider accent color -- not a config key; picked by provider name so the
# three common providers in the documented example look right out of the box.
# Anything else defaults to white. This is purely cosmetic (the hero eyebrow
# color) and has no effect on availability logic.
accent_for() {
  case "$(echo "$1" | tr '[:upper:]' '[:lower:]')" in
    netflix) echo "0xFFE50914" ;;
    prime|primevideo|amazon) echo "0xFF1A98FF" ;;
    *) echo "0xFFFFFFFF" ;;
  esac
}

mkdir -p "$GEN/com/lighthome/tv"
APPCONFIG="$GEN/com/lighthome/tv/AppConfig.java"

{
  echo "package com.lighthome.tv;"
  echo
  echo "import java.util.HashSet;"
  echo "import java.util.LinkedHashMap;"
  echo "import java.util.Map;"
  echo "import java.util.Set;"
  echo
  echo "// Generated by build.sh from config.properties on every build. Do not hand-edit."
  echo "// Lives under build/gen (NOT src) so no secret or machine-specific value ever"
  echo "// sits in version-controlled source. See config.example.properties for every key."
  echo "public final class AppConfig {"
  echo "    public static final String TMDB_KEY = \"$(esc "$TMDB_KEY")\";"
  echo "    public static final String REGION = \"$(esc "$REGION")\";"
  echo "    public static final String PRIORITY_COUNTRY = \"$(esc "$PRIORITY_COUNTRY")\";"
  echo "    public static final String PRIORITY_LABEL = \"$(esc "$PRIORITY_LABEL")\";"
  echo "    public static final int PRIORITY_MIN = $PRIORITY_MIN;"
  echo "    public static final int PRIORITY_MAX_AGE_MONTHS = $PRIORITY_MAX_AGE_MONTHS;"
  echo "    public static final int WINDOW_MONTHS = $WINDOW_MONTHS;"
  echo
  echo "    // {label, watchIds, pkg, region, accentColorHex} -- one row per \`providers=\` entry."
  echo "    private static final String[][] PROVIDER_DATA = {"
  if [ -n "$PROVIDERS_RAW" ]; then
    IFS=',' read -ra ENTRIES <<< "$PROVIDERS_RAW"
    for entry in "${ENTRIES[@]}"; do
      name="$(echo "$entry" | cut -d: -f1)"
      ids="$(echo "$entry" | cut -d: -f2)"
      pkg="$(echo "$entry" | cut -d: -f3)"
      region_override="$(cfg_get "${name}_region" "$REGION")"
      accent="$(accent_for "$name")"
      # Capitalize the first letter of the name for display (netflix -> Netflix).
      label="$(echo "${name:0:1}" | tr '[:lower:]' '[:upper:]')${name:1}"
      echo "        {\"$(esc "$label")\", \"$(esc "$ids")\", \"$(esc "$pkg")\", \"$(esc "$region_override")\", \"$(esc "$accent")\"},"
    done
  fi
  echo "    };"
  echo
  echo "    public static HeroSyncService.Provider[] buildProviders() {"
  echo "        HeroSyncService.Provider[] out = new HeroSyncService.Provider[PROVIDER_DATA.length];"
  echo "        for (int i = 0; i < out.length; i++) {"
  echo "            String[] d = PROVIDER_DATA[i];"
  echo "            int accent = (int) Long.parseLong(d[4].substring(2), 16);"
  echo "            out[i] = new HeroSyncService.Provider(d[0], d[1], d[2], d[3], accent);"
  echo "        }"
  echo "        return out;"
  echo "    }"
  echo
  echo "    /** Pipe-joined TMDB watch-provider ids for every configured provider whose"
  echo "     *  region matches the given one (e.g. the priority country). */"
  echo "    public static String providersForRegion(String region) {"
  echo "        StringBuilder sb = new StringBuilder();"
  echo "        for (String[] d : PROVIDER_DATA) {"
  echo "            if (d[3].equals(region)) {"
  echo "                if (sb.length() > 0) sb.append(\"|\");"
  echo "                sb.append(d[1]);"
  echo "            }"
  echo "        }"
  echo "        return sb.toString();"
  echo "    }"
  echo
  echo "    // keycode -> package, from \`remote_keys=\`. Empty by default: see RemoteKeys."
  echo "    public static final Map<Integer, String> REMOTE_KEYS = buildRemoteKeys();"
  echo "    private static Map<Integer, String> buildRemoteKeys() {"
  echo "        Map<Integer, String> m = new LinkedHashMap<>();"
  if [ -n "$REMOTE_KEYS_RAW" ]; then
    IFS=',' read -ra RK_ENTRIES <<< "$REMOTE_KEYS_RAW"
    for entry in "${RK_ENTRIES[@]}"; do
      code="$(echo "$entry" | cut -d: -f1 | tr -d '[:space:]')"
      pkg="$(echo "$entry" | cut -d: -f2 | tr -d '[:space:]')"
      if [ -n "$code" ] && [ -n "$pkg" ]; then
        echo "        m.put($code, \"$(esc "$pkg")\");"
      fi
    done
  fi
  echo "        return m;"
  echo "    }"
  echo
  echo "    // Hidden from the app grid by default, from \`default_hidden_packages=\`."
  echo "    public static final Set<String> DEFAULT_HIDDEN_PACKAGES = buildDefaultHidden();"
  echo "    private static Set<String> buildDefaultHidden() {"
  echo "        Set<String> s = new HashSet<>();"
  if [ -n "$DEFAULT_HIDDEN_RAW" ]; then
    IFS=',' read -ra HIDDEN_ENTRIES <<< "$DEFAULT_HIDDEN_RAW"
    for pkg in "${HIDDEN_ENTRIES[@]}"; do
      pkg_trimmed="$(echo "$pkg" | tr -d '[:space:]')"
      if [ -n "$pkg_trimmed" ]; then
        echo "        s.add(\"$(esc "$pkg_trimmed")\");"
      fi
    done
  fi
  echo "        return s;"
  echo "    }"
  echo
  echo "    private AppConfig() {}"
  echo "}"
} > "$APPCONFIG"

echo "==> Signing keystore"
umask 077
mkdir -p "$SIGNING_DIR"
chmod 700 "$SIGNING_DIR"
if [ ! -f "$KEYSTORE" ]; then
  # Random per-checkout password, stored only in the untracked signing/ folder
  # (never in the repo). debug-only signing -- this is NOT a release key.
  # (set +o pipefail in the subshell: `head -c24` closing the pipe early makes
  # `tr` exit via SIGPIPE/141, which pipefail would otherwise treat as a
  # failure of this whole command substitution under `set -e`.)
  KS_PASS="$(set +o pipefail; LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24)"
  echo "$KS_PASS" > "$KEYSTORE_PASS_FILE"
  chmod 600 "$KEYSTORE_PASS_FILE"
  "$KEYTOOL" -genkeypair -v \
    -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias lighthome -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=LightHome,OU=Dev,O=LightHome,C=US" \
    > /dev/null
  chmod 600 "$KEYSTORE"
  echo "    generated $KEYSTORE (password in $KEYSTORE_PASS_FILE, untracked)"
else
  KS_PASS="$(cat "$KEYSTORE_PASS_FILE")"
  echo "    reusing $KEYSTORE"
fi

echo "==> aapt2 compile"
"$AAPT2" compile --dir res -o "$RES_COMPILED"

echo "==> aapt2 link"
"$AAPT2" link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --java "$GEN" \
  --min-sdk-version 26 --target-sdk-version 34 \
  "$RES_COMPILED"/*.flat

echo "==> javac"
find src "$GEN" -name "*.java" > "$OUT/sources.list"
"$JAVAC" --release 11 -g:none -nowarn -classpath "$ANDROID_JAR" -d "$OBJ" @"$OUT/sources.list"

echo "==> d8"
find "$OBJ" -name "*.class" > "$OUT/classes.list"
"$D8" --release --lib "$ANDROID_JAR" --output "$OUT" @"$OUT/classes.list"

echo "==> package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)

echo "==> zipalign"
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> apksigner"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$OUT/Home.apk" "$OUT/aligned.apk"

echo ""
echo "Built $PROJECT_DIR/$OUT/Home.apk"
ls -la "$OUT/Home.apk"
