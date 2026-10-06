<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Website](https://nuvio.tv) · [GitHub releases](https://github.com/NuvioMedia/NuvioTV/releases/latest) · [Support Nuvio](https://nuvio.tv/support)

</div>

## Nuvio+ fork

This checkout is **Nuvio+**, the `hindsight2021/NuvioTV` fork. Development uses
`plus-dev`; the full build installs as `com.nuvio.tv.plus` alongside official Nuvio.

- [Nuvio+ releases and manual APK downloads](https://github.com/hindsight2021/NuvioTV/releases)
- [Project handover](HANDOVER.md)
- [0.9.4-plus.50 implementation and acceptance plan](RELEASE_PLAN_0.9.4-plus.50.md)

Release candidates require device acceptance before being described as stable.
Builds run through GitHub Actions; TV installation remains manual. Preserve the
existing signing identity and app data when upgrading. Android may reject a
downgrade to a lower versionCode; a previous APK alone is not a guaranteed rollback.

## Get official upstream Nuvio TV

- [Android TV on Google Play](https://play.google.com/store/apps/details?id=com.nuvio.app)
- [Android TV APK](https://github.com/NuvioMedia/NuvioTV/releases/latest)

## Build from source

```bash
git clone https://github.com/NuvioMedia/NuvioTV.git
cd NuvioTV
./gradlew :app:assembleFullDebug
```

Nuvio TV is built with Kotlin, Jetpack Compose, TV Material 3, and Media3. Development requires Android Studio, a JDK, and the Android SDK.

## License

[GNU General Public License v3.0](./LICENSE)
