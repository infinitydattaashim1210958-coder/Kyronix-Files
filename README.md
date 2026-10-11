# Kyronix Files

A production-grade Android file manager built in Kotlin.  
UI mirrors **Samsung My Files**. Crash-free on **MIUI HyperOS** (Redmi Note 14).  
Supports Android 5.0 (API 21) through Android 15 (API 35).

---

## Building via GitHub Actions

### 1 — Fork / push this repo to GitHub

```bash
git init
git add .
git commit -m "Initial commit"
git remote add origin https://github.com/YOUR_USERNAME/kyronix-files.git
git push -u origin main
```

### 2 — Generate a release keystore (do this once, locally)

```bash
keytool -genkeypair \
  -alias kyronix-key \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -keystore kyronix-release.jks \
  -storepass YOUR_STORE_PASSWORD \
  -keypass YOUR_KEY_PASSWORD \
  -dname "CN=Kyronix, OU=Mobile, O=Kyronix, L=City, ST=State, C=BD"
```

> **Never commit the `.jks` file** — it is in `.gitignore`.

### 3 — Encode the keystore as base64

```bash
# Linux / macOS
base64 -w0 kyronix-release.jks

# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("kyronix-release.jks"))
```

Copy the entire output string.

### 4 — Add GitHub Secrets

Go to your repo → **Settings → Secrets and variables → Actions → New repository secret**

| Secret name          | Value                                    |
|----------------------|------------------------------------------|
| `KEYSTORE_BASE64`    | The base64 string from step 3            |
| `KEY_STORE_PASSWORD` | Your `storepass` from step 2             |
| `KEY_ALIAS`          | `kyronix-key`                            |
| `KEY_PASSWORD`       | Your `keypass` from step 2               |

### 5 — Trigger builds

| Action | Result |
|--------|--------|
| Push to `main` or `develop` | Debug APK built, uploaded to Actions artifacts |
| Open a pull request to `main` | Same as above (CI gate) |
| Push a tag `v1.0.0` | Signed release APK + AAB built and attached to a GitHub Release |

```bash
# Create and push a release tag
git tag v1.0.0
git push origin v1.0.0
```

The GitHub Release is created automatically. Download the APK from the release page.

---

## Project structure

```
kyronix-files/
├── .github/
│   └── workflows/
│       └── build.yml          ← CI/CD pipeline
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/kyronix/files/
│       │   ├── KyronixApp.kt      ← Application class
│       │   ├── StorageHelper.kt   ← Permission handling (all API levels)
│       │   ├── FileItem.kt        ← Data model + FileType enum
│       │   ├── FileLoader.kt      ← Coroutine directory loader
│       │   ├── FileAdapter.kt     ← RecyclerView adapter
│       │   └── MainActivity.kt    ← Full UI + navigation
│       └── res/
│           ├── drawable/          ← Vector icons (9 file types + UI icons)
│           ├── layout/            ← activity_main, item_file_list, item_file_grid
│           ├── menu/              ← toolbar, bottom nav, file action menus
│           ├── values/            ← strings, colors, themes, dimens
│           ├── values-night/      ← dark mode color overrides
│           └── xml/               ← file_provider_paths.xml
├── gradle/wrapper/
│   └── gradle-wrapper.properties  ← Gradle 8.9
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew
├── gradlew.bat
└── CHANGELOG.md
```

## MIUI HyperOS crash fixes

| Crash | Root cause | Fix |
|-------|-----------|-----|
| `FileUriExposedException` | `file://` URI in Intent | All intents use `FileProvider` (`content://`) |
| `ActivityNotFoundException` | MIUI drops MANAGE_APP_ALL_FILES intent | 3-step fallback chain in `StorageHelper` |
| `NullPointerException` | `getExternalStorageDirectory()` returns null | Null-safe `?.takeIf { it.exists() }` |
| RecyclerView `IllegalStateException` | Adapter updated after `onSaveInstanceState` | `pendingItems` buffer, deliver on `onResume` |
| Toolbar `AttributeNotFoundException` | Samsung `app:titleCentered` not in MIUI | Only standard `MaterialToolbar` attributes used |

## Compatibility

- **Minimum:** Android 5.0 (API 21)
- **Target:** Android 15 (API 35)
- **Tested on:** Samsung One UI, MIUI HyperOS (Redmi Note 14), stock AOSP
