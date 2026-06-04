# Google Play publishing (IsaiVazhi)

## What ships to Play

- Upload an **Android App Bundle** (`.aab`), not a raw APK:  
  `./gradlew :app:bundleRelease`
- ONNX weights are **not** in the bundle; users download them from the  
  [onnx-model-v1](https://github.com/humorouslydistracted/isaivazhi/releases/tag/onnx-model-v1) GitHub release on first launch.

## Signing

1. Copy `keystore.properties.example` → `keystore.properties` (never commit).
2. Create an upload keystore (one-time):

```bash
keytool -genkey -v -keystore upload-keystore.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

3. Enable **Play App Signing** in Play Console (recommended).

## Play Console (human or API)

Each listing needs: privacy policy URL, Data safety form, content rating,
screenshots, 512×512 icon, and `applicationId` fixed before first upload.

**Package name:** decide `com.isaivazhi.app` vs `com.isaivazhi.app.kt` in
`app/build.gradle.kts` before the first production upload.

## Automated upload (optional)

With a [Google Play service account](https://developer.android.com/studio/publish/app-signing#api-access)
and Gradle Play Publisher, set env vars (see repo root `PLAY_SECRETS.md`) and run
the publish workflow once credentials are configured.
