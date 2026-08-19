![banner](fastlane/metadata/android/en-US/images/featureGraphic.png)

# mpvExtended
[![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/qznet/mpvEx-TV.svg?logo=github&label=GitHub&cacheSeconds=3600)](https://github.com/qznet/mpvEx-TV/releases/latest)
[![GitHub all releases](https://img.shields.io/github/downloads/qznet/mpvEx-TV/total?logo=github&cacheSeconds=3600)](https://github.com/qznet/mpvEx-TV/releases/latest)


**mpvExtended is a fork of [mpv-android](https://github.com/mpv-android/mpv-android), built on the libmpv library. It aims

> **This repo (`qznet/mpvEx-TV`) is a TV-remote adapted fork.** It swaps the bundled prebuilt mpv AAR for a **source-built [FongMi/mpv](https://github.com/FongMi/mpv) kernel (`fongmi` branch)**, built with [FongMi/mpv-android](https://github.com/FongMi/mpv-android) buildscripts. See [Custom playback kernel](#custom-playback-kernel) below.
to combine the powerful features of mpv with an easy to use interface and additional
features.**

## Custom playback kernel

This fork does **not** ship the prebuilt `mpv-android` AAR. Instead the native player is compiled from source by CI:

- **Kernel source:** [FongMi/mpv](https://github.com/FongMi/mpv) @ `fongmi` branch (Dolby Vision Profile 5/7 GPU mapping, AV1/Vulkan improvements, newer FFmpeg).
- **Build system:** [FongMi/mpv-android](https://github.com/FongMi/mpv-android) @ `fongmi` buildscripts (FFmpeg `release-9.0-fongmi`, CMake, extra deps: libaribcaption/libbluray/libarchive/libdvdnav/curl/uchardet/…).
- **JNI bridge:** `app/src/main/jni/` compiles `libplayer.so` + `libmpv.so`; the Java bindings live in `app/src/main/java/is/xyz/mpv/` (vendored from FongMi).
- **ABI:** only `armeabi-v7a` and `arm64-v8a` are built (TV target; FongMi's artifact/build only covers arm).
- The kernel source is **cloned fresh on every CI build**, so rebuilds always track the latest `fongmi` commit automatically.

## Upstream

This fork uses [mpv-android](https://github.com/mpv-android/mpv-android) as the canonical upstream for future sync work.
The old `mpvEx` fork is not treated as upstream.

Synced from two upstreams on every rebuild (see [Upstream sync policy](#upstream-sync-policy)):

- **App / UI:** [XIONGPEILIN/mpvExtended-android](https://github.com/XIONGPEILIN/mpvExtended-android) (this fork's direct parent).
- **Playback kernel:** [FongMi/mpv](https://github.com/FongMi/mpv) `fongmi` (source, fetched at build time) + [FongMi/mpv-android](https://github.com/FongMi/mpv-android) `fongmi` (buildscripts, re-vendored when changed).

Typical sync flow:

```bash
git fetch upstream
git log --oneline HEAD..upstream/master
```

- Simpler and Easier to Use UI
- Material3 Expressive Design
- Advanced Configuration and Scripting
- Enhanced Playback Features
- Picture-in-Picture (PiP)
- Background Playback
- High-Quality Rendering
- Network Streaming
- File Management
- Completely free and open source and without any ads or excessive permissions
- Media picker with tree and folder view modes
- External Subtitle support
- Zoom gesture
- External Audio support
- Search Functionality
- SMB/FTP/WebDAV support
- Custom Playlist management support

**This project is still in development and is expected to have bugs. Please report any bugs you find in
the [Issues](https://github.com/XIONGPEILIN/mpvEx/issues) section.**

---

## Installation

### Stable Release
Download the latest stable version from the [GitHub releases page](https://github.com/XIONGPEILIN/mpvEx/releases).

[![Download Release](https://img.shields.io/badge/Download-Release-blue?style=for-the-badge)](https://github.com/XIONGPEILIN/mpvEx/releases)

Or you can get the stable releases here

[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroidButtonGreyBorder_nofont.png" height="50" alt="Get it at IzzyOnDroid">](https://apt.izzysoft.de/packages/app.marlboroadvance.mpvex)

---

## Showcase
<div class="image-row" align="center">
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/player.png" width="98%" />
</div>

<div class="image-row" align="center" justify-content="space-between">
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/folderscreen.png" width="23.5%"/>
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/videoscreen.png" width="23.5%"/>
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/about.png" width="23.5%"/>
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/pip.png" width="23.5%"/>
</div>

<div class="image-row" align="center">
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/framenavigation.png" width="48.5%" />
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/chapters.png" width="48.5%" />
</div>

---

## Building

### Prerequisites

- JDK 17
- Android SDK with build tools 34.0.0+
- Git (for version information in builds)

### APK Variants

The app generates multiple APK variants for different CPU architectures:

- **arm64-v8a**: Modern 64-bit ARM devices (recommended for most users)
- **armeabi-v7a**: Older 32-bit ARM devices

> x86 / x86_64 variants are intentionally not built (TV target; the FongMi kernel build only covers arm).

---

## Releases

### Creating a Release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`
2. Build the release APK:
   ```bash
   ./gradlew assembleDefaultRelease -x lintVitalAnalyzeDefaultRelease -x lintVitalReportDefaultRelease -x lintVitalDefaultRelease
   ```
3. Commit and push the version change:
   ```bash
   git add app/build.gradle.kts && git commit -m "release: bump version to x.x.x" && git push
   ```
4. Create a tag and push:
   ```bash
   git tag vx.x.x && git push origin vx.x.x
   ```
5. Create a GitHub Release and upload APKs:
   ```bash
   gh release create vx.x.x \
     app/build/outputs/apk/default/release/app-default-arm64-v8a-release.apk \
     app/build/outputs/apk/default/release/app-default-armeabi-v7a-release.apk \
     --repo qznet/mpvEx-TV \
     --title "mpv NAS Player vx.x.x (FongMi kernel)" \
     --notes "mpv NAS Player vx.x.x — source-built FongMi/mpv kernel"
   ```

   > For this fork the published artifacts are **only `armeabi-v7a` + `arm64-v8a`** APKs (no x86/universal), released on `qznet/mpvEx-TV`.

---

## Acknowledgments

- [mpv-android](https://github.com/mpv-android)
- [mpvKt](https://github.com/abdallahmehiz/mpvKt)
- [Next player](https://github.com/anilbeesetti/nextplayer)
- [Gramophone](https://github.com/FoedusProgramme/Gramophone)

## Upstream sync policy

This fork is kept in sync with upstream automatically on every rebuild:

- **Kernel (`FongMi/mpv` @ `fongmi`):** fetched fresh at build time — always latest, no manual step.
- **Buildscripts (`FongMi/mpv-android` @ `fongmi`):** re-vendored into `buildscripts/` when the upstream commit changes.
- **App / UI (`XIONGPEILIN/mpvExtended-android`):** upstream commits are merged into this branch when they change (conflict-free merge; conflicts are reported for manual resolution).

A scheduled check detects new upstream commits and triggers a rebuild + release. Tracked refs:
`FongMi/mpv@fongmi`, `FongMi/mpv-android@fongmi`, `XIONGPEILIN/mpvExtended-android@main`.

## Star History <img src="https://raw.githubusercontent.com/Tarikul-Islam-Anik/Animated-Fluent-Emojis/master/Emojis/Travel%20and%20places/Star.png" alt="Star" width="25" height="25" />

<a href="https://www.star-history.com/#XIONGPEILIN/mpvEx&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=XIONGPEILIN/mpvEx&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=XIONGPEILIN/mpvEx&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=XIONGPEILIN/mpvEx&type=date&legend=top-left" />
 </picture>
</a>
