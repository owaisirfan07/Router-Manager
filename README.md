# Router Manager

Android app to view **all** the info from your Huawei HG8546M router's admin
panel (home dashboard, system info, WAN, optical, devices, WLAN, LAN, security,
routes, forward rules, applications, system management, maintenance) and change
the main WiFi settings (name, password, broadcast, WMM, WPS, device limit,
security mode, on/off) without opening the full admin panel.

See **[FEATURES.md](FEATURES.md)** for the full list of what's included and
what's not.

## How it works

It talks directly to the router's own web admin endpoints (`login.cgi`,
`WlanBasic.asp`, `set.cgi`) over your home WiFi/LAN - same thing the
browser-based admin panel does, just with a simpler UI. Your phone must be
connected to the same network as the router.

## Automatic updates (one-time setup)

Every push to `main` makes GitHub build a new version and publish it as a
**Release**. When you open the app, it checks that Release and shows
**"Update available → Update now"**. The app downloads the APK and opens the
installer, you just tap **Install**. No tags, no version numbers to change.

All builds must be signed with the **same key**, otherwise Android says
"App not installed". The key is kept in GitHub Secrets (never in the code).

### 1. Add 2 secrets (once)
Repo → **Settings → Secrets and variables → Actions → New repository secret**

| Name | Value |
|---|---|
| `SIGNING_KEYSTORE` | the whole text inside `keystore-base64.txt` |
| `SIGNING_PASSWORD` | the text inside `password.txt` |

Keep `routermanager-release.jks` + the password somewhere safe (Google Drive
etc.). If you lose them, you'll have to uninstall the app once and use a new key.

### 2. Push the code
Push to `main`. Open the **Actions** tab, and the "Build & Release APK" run
should go green. A new Release (e.g. `v1.1.5`) appears with `RouterManager.apk`.

### 3. Install once (switch to the new key)
The app on your phone now was signed with GitHub's random debug key, so it
can't update itself to the new key. **Uninstall it once**, then install
`RouterManager.apk` from the latest Release.
From now on, updates come through the app.

### After that
- Change code → commit → push to `main` → wait ~3 min for Actions.
- Open the app → "Update available" → **Update now** → **Install**.
- First time only, Android asks to allow "Install unknown apps" for this app.
- You can also tap **"Version … · Check for updates"** at the bottom of the app.
- Editing only `.md` files doesn't make a new version.

## Using the app

1. Make sure your phone is on the same WiFi as the router.
2. Open the app, leave the router IP as `192.168.100.1` (change if yours
   differs).
3. Enter the admin username/password (e.g. `telecomadmin` / `admintelecom`,
   or your custom one if you changed it).
4. Tap Login - the home dashboard opens with the menu below it.
5. Tap any menu item to see that page. "Show all raw data" shows every field
   the router sent.
6. To change WiFi: Advanced: WLAN > WLAN Basic Configuration > Edit WiFi
   settings, change what you like and tap Save Settings.

**Note:** changing SSID/password/security mode will disconnect every device
currently on that WiFi - they'll need to reconnect with the new details
afterward.

## How the info pages work

`HwParser.kt` reads the data the router embeds in each page as JavaScript
(`function stX(field1, field2...)` + `new stX("value1", "value2"...)`), so field
names come from the router itself. `Sections.kt` lists every menu page, which
router URLs it loads, and the labels/formatting. To add or fix a page, edit
`Sections.kt` only.

## Notes / limitations

- Only manages the main 2.4G SSID (`WLANConfiguration.1`) for now.
- Encryption is fixed to AES for all security modes (Open/WEP/RADIUS modes
  aren't supported - PSK modes only).
