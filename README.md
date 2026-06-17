![banner](fastlane/metadata/android/en-US/images/featureGraphic.png)

# mpvExtended
[![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/XIONGPEILIN/mpvEx.svg?logo=github&label=GitHub&cacheSeconds=3600)](https://github.com/XIONGPEILIN/mpvEx/releases/latest)
[![GitHub all releases](https://img.shields.io/github/downloads/XIONGPEILIN/mpvEx/total?logo=github&cacheSeconds=3600)](https://github.com/XIONGPEILIN/mpvEx/releases/latest)


**mpvExtended is a fork of [mpv-android](https://github.com/mpv-android/mpv-android), built on the libmpv library. It aims
to combine the powerful features of mpv with an easy to use interface and additional
features.**

## Upstream

This fork uses [mpv-android](https://github.com/mpv-android/mpv-android) as the canonical upstream for future sync work.
The old `mpvEx` fork is not treated as upstream.

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

- **universal**: Works on all devices (larger size)
- **arm64-v8a**: Modern 64-bit ARM devices (recommended for most users)
- **armeabi-v7a**: Older 32-bit ARM devices
- **x86**: Intel/AMD 32-bit devices
- **x86_64**: Intel/AMD 64-bit devices

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
     app/build/outputs/apk/default/release/app-default-universal-release.apk \
     app/build/outputs/apk/default/release/app-default-x86_64-release.apk \
     app/build/outputs/apk/default/release/app-default-x86-release.apk \
     --repo XIONGPEILIN/mpvExtended-android \
     --title "mpv NAS Player vx.x.x" \
     --notes "mpv NAS Player vx.x.x"
   ```

---

## Acknowledgments

- [mpv-android](https://github.com/mpv-android)
- [mpvKt](https://github.com/abdallahmehiz/mpvKt)
- [Next player](https://github.com/anilbeesetti/nextplayer)
- [Gramophone](https://github.com/FoedusProgramme/Gramophone)

## Star History <img src="https://raw.githubusercontent.com/Tarikul-Islam-Anik/Animated-Fluent-Emojis/master/Emojis/Travel%20and%20places/Star.png" alt="Star" width="25" height="25" />

<a href="https://www.star-history.com/#XIONGPEILIN/mpvEx&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=XIONGPEILIN/mpvEx&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=XIONGPEILIN/mpvEx&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=XIONGPEILIN/mpvEx&type=date&legend=top-left" />
 </picture>
</a>
