# Router Manager

Android app to view/change your Huawei HG8546M router's main WiFi settings
(name, password, broadcast, WMM, WPS, device limit, security mode, on/off),
without opening the full admin panel.

## How it works

It talks directly to the router's own web admin endpoints (`login.cgi`,
`WlanBasic.asp`, `set.cgi`) over your home WiFi/LAN - same thing the
browser-based admin panel does, just with a simpler UI. Your phone must be
connected to the same network as the router.

## First-time setup (push this to your GitHub repo)

```bash
cd Router-Manager
git init
git add .
git commit -m "Initial Router Manager app"
git branch -M main
git remote add origin https://github.com/owaisirfan07/Router-Manager.git
git push -u origin main
```

Go to the repo's **Actions** tab - a build starts automatically. Once done,
open the run and download the `RouterManager-debug-apk` artifact, then
install the APK on your phone (allow "install from unknown sources" once).

## Getting future updates (no more manual zip downloads)

The app checks GitHub for a newer version every time you open it, and shows
a banner with a Download button if one exists. For that to work, updates
need to be published as a **GitHub Release** (not just a plain push), like
this:

```bash
# after making changes and committing them:
git tag v1.1
git push origin v1.1
```

Pushing a tag starting with `v` makes the workflow automatically build the
APK and attach it to a new GitHub Release. The next time you open the app,
it'll detect `v1.1` is newer than what's installed and offer the download.
You still have to tap install yourself (Android doesn't allow silent
self-updating for APKs installed outside the Play Store), but you won't
need to come back here for the file each time.

Keep the version number in `app/build.gradle.kts` (`versionName`) matching
the tag you push (e.g. tag `v1.1` -> `versionName = "1.1"`), so the app can
tell it's already on that version.

## Using the app

1. Make sure your phone is on the same WiFi as the router.
2. Open the app, leave the router IP as `192.168.100.1` (change if yours
   differs).
3. Enter the admin username/password (e.g. `telecomadmin` / `admintelecom`,
   or your custom one if you changed it).
4. Tap Login - your current settings load automatically.
5. Change whatever you like (SSID, password, broadcast, WMM, WPS, device
   limit, security mode, WiFi on/off) and tap Save Settings.

**Note:** changing SSID/password/security mode will disconnect every device
currently on that WiFi - they'll need to reconnect with the new details
afterward.

## Notes / limitations

- Only manages the main 2.4G SSID (`WLANConfiguration.1`) for now.
- Encryption is fixed to AES for all security modes (Open/WEP/RADIUS modes
  aren't supported - PSK modes only).
