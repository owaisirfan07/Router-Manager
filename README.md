# Veyron Monitor

Fast, native Android app jo Inverex Veyron II ka live data seedha asal
i.Solar cloud backend (tumcapp.com) se laati hai -- reverse-engineered
seedha i.Solar app k andar se.

## Kaise build karein

### Option A — Bina Android Studio k (sirf browser se, GitHub Actions free service)

1. github.com pe free account banayein (agar nahi hai).
2. Naya repository banayein.
3. "Add file > Upload files" se is poori `VeyronMonitor` folder ka pura
   content upload kar dein (`.github` folder samet -- hidden files ON
   karke) aur commit kar dein.
4. Repo k "Actions" tab pe jayein — "Build APK" workflow khud chal jaye ga.
5. Complete hone pe "Artifacts" section se **VeyronMonitor-APK** download
   kar lein.
6. APK phone pe install kar lein ("install unknown apps" allow karke).

### Option B — Android Studio se

1. Android Studio kholein, `File > Open` se `VeyronMonitor` folder select
   karein, sync hone dein, phone connect karke Run dabayein.

## Pehli baar use

- Apna **i.Solar** app wala username/password dal k login karein.
- App har 15 second mein latest data cloud se fetch karay gi.
- Filhal dashboard **saare fields raw list mein** dikhata hai (jo bhi
  naam se cloud data bhejta hai) -- kyun k Inverex Veyron II ka exact
  data-schema abhi confirm ho raha hai. Ek baar asal data dekh lein tou
  isay pretty icon-cards mein badalna aasan hai.

## Architecture

- `api/TumcApi.kt` — asal i.Solar backend (tumcapp.com) k liye
  reverse-engineered login + SHA-256 signing + device-list + live-data
- `model/Models.kt` — data classes
- `data/CredentialStore.kt` — local credential storage
- `ui/` — Jetpack Compose screens (Login + Dashboard)
- `MainActivity.kt` — state management + auto-refresh polling loop
