# News Faces · Android

Native Kotlin / Jetpack Compose version of the News Faces web app.

- **Gallery**: 70 news sections fetched directly (full RSS item lists, no proxies), colour analysis, sort by time / hue / lightness / vividness / warmth / source, pinch the grid to change column count, tap a photo for an unbounded pinch-zoom viewer with swipe.
- **Face**: front camera → ML Kit face detection (all faces) + selfie segmentation for an organic head outline → live mosaic of news photos matched by colour in Lab space. Hue / saturation / brightness / contrast / tint shift the target, never the photos. Drag heads individually, pinch to zoom them together, "Arrange" resets to an equal-size grid. "Photo" runs the same pipeline on a picked image.
- **Record**: writes the live mosaic to an H.264 MP4 in `Movies/NewsFaces` via MediaRecorder's surface input.
- Side drawer with sliders for every parameter; settings and the photo cache persist.

## Build

Open `android/` in Android Studio, or:

```bash
cd android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

minSdk 29, compileSdk 36, Kotlin 2.1, AGP 8.11, Compose BOM 2025.06. The debug APK is large because ML Kit's models are bundled.
