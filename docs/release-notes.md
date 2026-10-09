## SHADOW INJECTOR 1.0.1

**Fixes**

- Every build is now signed with the same key, so the in-app updater can actually install
  updates instead of failing with "App not installed"
- The release APK is signed and installable (it used to be `app-release-unsigned.apk`)

**Included from 1.0.0**

- Dark UI with animated starfield
- Live overlay-permission, root and payload status
- Single-select targets: Free Fire, Free Fire MAX, Free Fire Advanced
- Injector waits for the Unity runtime to be mapped before injecting
- Draggable `SHADOW` status chip over the game
- Telegram promo on first launch, self updater on every launch

---

## Requirements

- Android 8.0+ (arm64)
- Rooted device (Magisk / KernelSU / APatch)
- "Display over other apps" for this app
- "Install unknown apps" for this app, if you want the in-app updater to self-install

## Targets

- Free Fire — `com.dts.freefireth`
- Free Fire MAX — `com.dts.freefiremax`
- Free Fire Advanced — `com.dts.freefireadv`

---

Join Telegram for updates: https://t.me/+BBimnHMiSvpiYTBl
