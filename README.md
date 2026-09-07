<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Boomio" width="300" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Boomio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [GitHub releases](https://github.com/animaldev528/NuvioTV/releases) · [Report an issue](https://github.com/animaldev528/NuvioTV/issues)

</div>

> **Independent fork.** Boomio is an open-source fork of [Nuvio TV](https://github.com/NuvioMedia/NuvioTV)
> by Nuvio Media, focused on integration with the boomio self-hosted platform (companion remote,
> watch-party, library sync, and per-profile controls). It is **not affiliated with or endorsed by
> Nuvio Media**. Nuvio TV remains the upstream project; upstream changes are merged into this fork.

## Get Boomio

- [Latest APK (GitHub Releases)](https://github.com/animaldev528/NuvioTV/releases/latest)
- Android TV (Play) listing: not currently published — install the release APK above.

> The official Nuvio TV app is [on Google Play](https://play.google.com/store/apps/details?id=com.nuvio.app).
> Boomio ships under its own package (`com.boomio.tv`) so it can be installed alongside the official app.

## Build from source

```bash
git clone https://github.com/animaldev528/NuvioTV.git
cd NuvioTV
git checkout boomio-rebrand
./gradlew :app:assembleFullDebug
```

Boomio is built with Kotlin, Jetpack Compose, TV Material 3, and Media3. Development requires Android Studio, a JDK, and the Android SDK.

## Differences from upstream Nuvio TV

- Rebranded application identity (Boomio) under the `com.boomio.tv` package.
- Boomio backend integrations for companion remote, watch-party, and shared library management.
- See the commit history on this fork's branches for the full set of changes on top of upstream.

## License & attribution

- Boomio is distributed under the [GNU General Public License v3.0](./LICENSE).
- App code and brand are derived from [Nuvio TV](https://github.com/NuvioMedia/NuvioTV) © Nuvio Media.
