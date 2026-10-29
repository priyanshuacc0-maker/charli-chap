# Charlie Chaap — Android app

This folder now contains a native Android wrapper around the Charlie Chaap web app, built automatically by GitHub Actions.

## What the app does

The app has two modes:

1. **Connected (normal use).** You enter the LAN address of the order-desk computer, for example
   `http://192.168.1.25:3000`, and the app opens the customer site served by that machine. Orders,
   menu and statuses are shared with every phone and the staff dashboard on the same Wi-Fi.
2. **Offline.** If no address is saved, the app opens the menu bundled inside the APK. The cart then
   lives on that phone only — useful for showing the menu or taking a quick order when the server
   is off.

The app needs no internet permission beyond the local network, and no third-party libraries.

## Getting the APK

Every push to `main` that touches the app builds a signed APK and publishes it as a GitHub Release:

* Releases page: `https://github.com/priyanshuacc0-maker/charli-chap/releases`
* Or run the workflow manually: **Actions → Android APK → Run workflow**.

## Updating over an older version

Updates work because:

* `applicationId` stays `com.charlichaap.app` — Android treats it as the same app.
* Every build is signed with the **same keystore** (`KEYSTORE_BASE64` repository secret).
* `versionCode` increases with every build: `1000 + GITHUB_RUN_NUMBER`.

So installing a newer APK over an older one is a normal update: Android replaces the app, keeps its
data, and shows the new version in **⋮ → About**.

Never change the keystore once you have shipped a build — Android will refuse to install an APK
signed with a different key over an existing one (you would have to uninstall first).

## Repository secrets used by the build

| Secret | Purpose |
| --- | --- |
| `KEYSTORE_BASE64` | base64 of `cc-release.keystore` (PKCS12) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `charlichaap` |
| `KEY_PASSWORD` | key password |

The keystore and its password are also included in the source zip you were given, in
`signing/cc-release.keystore` and `signing/SIGNING.txt`. Keep that zip private — anyone with the
keystore can sign an APK that Android will accept as an update to your app.

## Building locally

```bash
export KEYSTORE_FILE=/absolute/path/cc-release.keystore
export KEYSTORE_PASSWORD='...' KEY_ALIAS=charlichaap KEY_PASSWORD='...'
gradle :app:assembleRelease -PappVersionCode=1050 -PappVersionName=1.0.50
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`. Pass a `versionCode` higher than
the last published one, or Android will treat it as a downgrade.

## Why `web/index.html.part-*` exists

`public/index.html` is 9.2 MB, which is larger than a single GitHub API commit can carry. The file is
therefore stored split into four byte-exact parts under `web/`. The workflow reassembles them with
`cat` before building. If `public/index.html` is present (for example after you commit it from the
web UI), the build uses that file and ignores the parts.

## Source layout

```
app/src/main/java/com/charlichaap/app/MainActivity.java   WebView client, setup dialog, version info
app/src/main/AndroidManifest.xml                          permissions, cleartext HTTP for LAN
app/src/main/res/mipmap-*/ic_launcher.png                 launcher icons
.github/workflows/android.yml                             signed release build + GitHub Release
```
