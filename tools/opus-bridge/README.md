# libboomioopus — the private-listening fork's codec

`libboomioopus.so` is a **prebuilt** committed into this repo's `jniLibs`. Gradle does
not build it. This directory is the recipe that produces it, kept with the binary so the
two cannot drift apart.

## What it is

A thin JNI surface over [libopus](https://opus-codec.org/) 1.5.2 (BSD-3), exposing the
class `com.boomio.opus.OpusCodec`. The TV *encodes* the private-listening audio fork with
it; the phone *decodes* it.

Both apps load the **same** `.so`. JNI derives its symbol names from the Java class name,
so declaring one shared package name in both trees means one set of symbols and one
binary, instead of two builds that must be kept in step.

Audio is captured at 48 kHz in 20 ms frames at 128 kbit/s VBR, complexity 10,
`OPUS_SIGNAL_MUSIC`, in-band FEC **off**. `jni_bridge.c` records why, including why FEC
is off: it is a structural quality cap, not a bit-budget cost.

## Rebuilding

On the build box:

    curl -LO https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz
    sha256sum opus-1.5.2.tar.gz
    # expect 65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
    bash build-opus-android.sh
    # then copy out/<abi>/libboomioopus.so over the jniLibs copies

`build-opus-android.sh` needs NDK `29.0.14206865` and CMake `3.22.1` under
`$HOME/Android/Sdk`. It builds `android-24` (both clients' `minSdk`) for all four ABIs,
links with `-z max-page-size=16384` for 16 KB-page devices (Android 15+), and fails
loudly if the NDK or the tarball is missing. It never downloads anything itself.

## Layout

- `jni_bridge.c` — every JNI entry point; the only file that knows the Java class name.
  A handle is an opaque `jlong`; `0` means "failed to create". No entry point throws —
  a negative return is a libopus error code, and the Kotlin side falls back on it.
- `build-opus-android.sh` — per-ABI libopus static build, then the link.
- The `<abi>/libboomioopus.so` files themselves live in the app's `jniLibs`, not here.
