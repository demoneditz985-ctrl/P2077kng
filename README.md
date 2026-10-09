# SHADOW INJECTOR

Black/dark Android launcher that stages and injects the repo's own native libraries into
Garena Free Fire, Free Fire MAX or Free Fire Advanced Server on a **rooted** device.

```
libRootMagisk.so  ->  injector binary   (PIE executable: ptrace + /proc/<pid>/maps + dlopen)
libmain.so        ->  payload           (Unity / IL2CPP: il2cpp_*, libunity.so, libEGL, libGLESv2)
```

## What the app does

1. **Animated background** — a 3D starfield (drifting stars, twinkle, nebula glow, occasional
   shooting star) drawn on a plain `Canvas` in `StarfieldView.kt`. No OpenGL, no deps.
2. **Overlay permission** — checks `Settings.canDrawOverlays()`, opens the system page to
   grant it, shows a live **GRANTED / DENIED** pill. After a successful injection it draws a
   small draggable `SHADOW ● <target>` chip over the game (`OverlayService`). Tap it to dismiss.
3. **Root status** — runs `id -u` through `su`, shows **GRANTED · MAGISK / KSU / APATCH**
   or **NOT FOUND**, plus a RECHECK button.
4. **Payload status** — confirms `libmain.so` + `libRootMagisk.so` really got extracted from
   the APK (`useLegacyPackaging = true` is required for this).
5. **Three exclusive targets** — FREE FIRE, FREE FIRE MAX, FREE FIRE ADVANCED. Only one can be
   selected at a time; each row shows whether that package is installed.
6. **INJECT** — disabled until overlay + root + payload + a target are all green. It then:
   - copies both libs to `/data/local/tmp/shadow` (payload → `libmain.so`, injector →
     `shadow-injector`), `chmod 0755` and relabels them with `chcon`,
   - force-stops the game and relaunches it,
   - polls for the game PID,
   - runs `shadow-injector <pid> /data/local/tmp/shadow/libmain.so`,
   - shows the injector's stdout/stderr in the on-screen session log.
7. **Session log** — a colour-coded, timestamp-free running record of every step
   (this is the "recorder": every staging / launch / inject step is written to it).

## Building

### On GitHub Actions (recommended — it just works)

Every push builds the APK. The workflow at `.github/workflows/build.yml` installs
everything from scratch and uploads the result:

* **JDK 17 (Temurin)** — the baseline AGP 8.x requires
* **Android SDK 34** + `build-tools;34.0.0` + `platform-tools`
* **Gradle 8.4**

```bash
# trigger it by hand, or just push
gh workflow run build.yml
gh run watch          # follow the build
gh run download       # pulls the APK artifact
```

Artifacts uploaded by each run:

| Artifact | Contents |
| --- | --- |
| `shadow-injector-apk` | `app/build/outputs/apk/{debug,release}/*.apk` |
| `gradle-wrapper` | `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` — commit these once and you can use `./gradlew` locally without installing Gradle |

### Locally

Open the folder in **Android Studio (Iguana or newer)** and press Run, or from a shell:

```bash
gradle wrapper                       # once: generates gradle/wrapper/gradle-wrapper.jar
./gradlew generateLauncherIcons      # rebuilds icons from logo/logo.png
./gradlew assembleDebug              # -> app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK 34, AGP 8.1.4, Kotlin 1.9.22, Gradle 8.4.
`minSdk 26`, and `abiFilters 'arm64-v8a'` because both shipped libraries are arm64 only.

> `gradle/wrapper/gradle-wrapper.jar` is a binary and is not committed — grab it from the
> `gradle-wrapper` artifact of any Actions run, or run `gradle wrapper` once.

## Tuning

Everything adjustable lives in
`app/src/main/java/com/shadow/injector/InjectorConfig.kt`:

| Constant | Meaning |
| --- | --- |
| `WORK_DIR` | where the libs are staged (`/data/local/tmp/shadow`) |
| `ARG_TEMPLATES` | argument order handed to the injector binary |
| `PID_TIMEOUT_MS` | how long to wait for the game process |
| `RETRY_PERMISSIVE` | retry once with `setenforce 0` if dlopen is blocked by SELinux |

**Argument order.** The binary resolves its target with `atoi()` and then reads
`/proc/<pid>/maps`, so `<pid> <lib>` is almost certainly correct. The app tries each template
in `ARG_TEMPLATES` in turn and falls through to the next one whenever the injector prints a
usage error, so if your build wants flags (e.g. `-p <pid> -l <lib>`) just add it to the list.

## Swapping the logo

**Replace one file: `logo/logo.png`.** That is it.

The `generateLauncherIcons` Gradle task (wired into `preBuild`, and run explicitly by CI)
resizes it into every asset the app uses:

| Generated file | Size | Used for |
| --- | --- | --- |
| `app/src/main/res/drawable-nodpi/logo.png` | 512×512 | in-app header |
| `app/src/main/res/drawable-nodpi/ic_launcher_foreground.png` | 432×432, artwork in the centre 264×264 safe zone | adaptive icon |
| `app/src/main/res/mipmap-*/ic_launcher{,_round}.png` | 48/72/96/144/192 | legacy launcher icons |

```bash
# drop your artwork in, then
./gradlew generateLauncherIcons
```

Square artwork works best; anything else is stretched to fit. If your logo is not on black,
also edit `res/drawable/ic_launcher_background.xml` to match it.

## Notes

* The libraries were moved from the repo root into
  `app/src/main/jniLibs/arm64-v8a/` so Gradle packages them into the APK.
* Injection is done by *executing* `libRootMagisk.so` (it is a PIE binary, not a shared
  library) as root; it then `dlopen()`s `libmain.so` inside the game process.
* If staging or injection fails, read the session log — the injector's own output is printed
  there verbatim.
* CI prints an APK listing for every successful build: both `.so` files must appear under
  `lib/arm64-v8a/` and the merged manifest must carry `extractNativeLibs=true`, otherwise the
  payload never reaches `nativeLibraryDir` and the app has nothing to copy out.
