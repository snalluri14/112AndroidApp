# Ambulance Dispatch – Android app

Package `com.ambulance.dispatch`.

## Setup (once)

1. Install Android Studio and JDK 21.
2. Create `local.properties` in this folder (copy `local.properties.example`):

| Key | Purpose |
|---|---|
| `MAPS_API_KEY` | Google Maps API key |
| `SERVER_URL` | Server address shown on the app's first screen, e.g. `http://192.168.0.125:8000` |
| `ADMIN_PIN` | PIN to change the vehicle ID on a phone |
| `ALLOW_HTTP` | `true` while the server uses `http://` |

3. Keep `keystore/ambulance.jks` and `keystore.properties`. They sign every build. Back them up:
   if lost, installed apps can't be updated.

## Build the APK

PowerShell:
```
$env:JAVA_HOME="C:\Program Files\Java\jdk-21"
cd c:\Service\android
.\gradlew.bat assembleDebug
```
Or in Android Studio: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

Output: `app\build\outputs\apk\debug\app-debug.apk`

## Install on a phone

1. Start the server (see `server/README.md`).
2. On the phone open `http://<PC IP>:8000/download` → install (allow "install unknown apps").
3. Open the app → enter vehicle ID → tap **Server: … (change)** if the address is wrong → **Save and continue**.
4. Allow notifications and location.

Installing a new APK over the old one keeps the saved vehicle ID and server address.
To change them: menu **⋮ → Change vehicle ID** (needs the PIN).

## Phone settings

- Xiaomi / Vivo / Oppo / Realme: turn on **Autostart** and set battery to **No restrictions**,
  otherwise dispatch alerts may not arrive when the app is closed.
