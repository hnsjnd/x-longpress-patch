# x-longpress-patch

"Long press video speed" patch for the official X (Twitter) Android app (`com.twitter.android`).
Hold a finger on a playing video → 2x speed with a small "2x ▶▶" hint; release → 1x.
Tap / double-tap / scroll are untouched (250 ms timer + touch-slop move cancel).

This repo contains **only original patch code** — no X APK or X code. You supply your own APK.

## How it works
- `patch.py` fingerprints obfuscated Media3 classes in an apktool decode:
  - ExoPlayerImpl = class in `androidx/media3/exoplayer` with `"ExoPlayerImpl"` + `AndroidXMedia3/` strings
  - PlaybackParameters = class with `PlaybackParameters(speed=` toString
  - setter = `public final ?(PlaybackParameters)V` on ExoPlayerImpl
- Injects `LongPressSpeed.registerPlayer(this)` at the end of the ExoPlayerImpl constructor (weak list of players),
  and `LongPressSpeed.init(app)` at the start of `Application.onCreate`.
- `init` registers ActivityLifecycleCallbacks and wraps each Activity's `Window.Callback`.
  `dispatchTouchEvent` arms a 250 ms timer when ACTION_DOWN lands on a TextureView/SurfaceView/*PlayerView;
  moves > touch slop or a second pointer cancel it. On fire, every registered player with `isPlaying()` gets
  `setPlaybackParameters(new PlaybackParameters(2f,1f))`; UP/CANCEL restores 1f and is delivered as CANCEL
  so the long-press is not treated as a tap.
- The extension (`extension/src`) is compiled with javac + d8 and added as an extra `classesN.dex`.

Tested against X 12.36.1-prod.01 (Media3: ExoPlayerImpl=`h0`, PlaybackParameters=`x0`, setter=`c`).

## Build
```
./build.sh X_12.36.1.apkm my.jks storepass   # -> out/X-longpress.apk
```
Needs JDK 17, Android SDK build-tools (aapt, d8, zipalign, apksigner), Python 3.
