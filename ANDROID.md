# Charlie Chaap — Android app

The app **is** the order desk. One Android phone runs the whole service: the menu website, the
staff dashboard and the order API. Other phones on the same Wi-Fi or hotspot open the address the
app shows. No computer, no internet, no cloud account.

## What is inside the APK

| Part | What it does |
| --- | --- |
| `ServerService` | Foreground service that keeps the HTTP server alive with the screen off |
| `core/CharlieServer` | The HTTP + REST API server (plain Java, no dependencies) |
| `core/Store` | Menu, order, config and backup rules — the same rules as `server.js` |
| `MainActivity` | Start/stop, address, share, staff password, settings |
| `ClientActivity` | WebView for the customer menu, staff dashboard and owner controls |
| `BootReceiver` | Optional auto-start after the phone reboots |
| `assets/index.html` | The whole web app, embedded (9.2 MB) |
| `assets/menu.json` | Starter menu, copied into app storage on first run |

Data lives in the app's private storage (`files/data/state.json`) and survives updates.

## First run

1. Open the app, tap **Staff password**, and set one of at least 12 characters. Write it down.
2. Turn on the phone's hotspot (or join the restaurant Wi-Fi).
3. Tap **Start order desk**. The home screen shows an address such as
   `http://192.168.43.1:3000/customer`.
4. On any other phone joined to that hotspot, open that address.
5. Tap **Keep running in background** once, and leave the phone on charge.

## Updates

Every push that touches `app/` builds a signed APK and publishes it as a GitHub Release:

* Releases: `https://github.com/priyanshuacc0-maker/charli-chap/releases`
* Manual run: **Actions → Android APK → Run workflow**

Install the new APK over the old one. That works because:

* `applicationId` stays `com.charlichaap.app`
* every build is signed with the same keystore (`KEYSTORE_BASE64` secret)
* `versionCode` rises with each build (`1000 + GITHUB_RUN_NUMBER`)

Never replace the keystore once a build has shipped — Android would then refuse the update and
require an uninstall, losing the orders on that phone.

## Repository secrets

| Secret | Purpose |
| --- | --- |
| `KEYSTORE_BASE64` | base64 of the PKCS12 keystore |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `charlichaap` |
| `KEY_PASSWORD` | key password |

A copy of the keystore and its passwords is in the source zip under `signing/`. Keep that private.

## Building locally

```bash
export KEYSTORE_FILE=$PWD/signing/cc-release.keystore
export KEYSTORE_PASSWORD='...' KEY_ALIAS=charlichaap KEY_PASSWORD='...'
gradle :app:assembleRelease -PappVersionCode=1100 -PappVersionName=1.0.100
```

## Testing the server without a phone

The server classes use no Android APIs, so the API can be exercised on a desktop JVM:

```bash
javac -cp json.jar -d out $(find app/src/main/java/com/charlichaap/app/core -name '*.java')
java -cp out:json.jar <your harness>      # start CharlieServer on a port and hit the endpoints
```

## Why `web/index.html.part-*` exists

`public/index.html` is 9.2 MB — larger than one GitHub API commit can carry — so it is stored split
into four byte-exact parts. The workflow reassembles them into `app/src/main/assets/index.html`
before building. If `public/index.html` is present, that file wins.

## The PC version

`server.js` in the repository root is the same service for a computer, for anyone who later wants a
router plus a laptop. The two share the same API and the same `index.html`.
