## SHADOW INJECTOR

Root based injector launcher for Garena's Free Fire clients.

**Targets**

- Free Fire — `com.dts.freefireth`
- Free Fire MAX — `com.dts.freefiremax`
- Free Fire Advanced — `com.dts.freefireadv`

**Requirements**

- Android 8.0+ (arm64)
- Rooted device (Magisk / KernelSU / APatch)
- "Display over other apps" permission for this app

**What's in this build**

- Black / dark UI with an animated 3D starfield background
- Live overlay-permission, root and payload status
- Single-select targets; INJECT restages the payload, relaunches the game, waits for the
  Unity runtime to be mapped, then runs the injector
- Draggable `SHADOW` status chip drawn over the game
- Colour-coded session log of every step
- Self updater: on launch it reads `version.json` from the project page and offers any newer build

**Assets**

- `app-debug.apk` — debuggable build
- `app-release-unsigned.apk` — release build, unsigned

---

Join Telegram for updates: https://t.me/+BBimnHMiSvpiYTBl
