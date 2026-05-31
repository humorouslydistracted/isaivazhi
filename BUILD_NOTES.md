# MakuluApp Build Notes

## Project State

This repository contains an Android native app built with:

- Android Gradle Plugin `8.5.2`
- Kotlin `2.0.0`
- Compile SDK `35`
- Min SDK `31`
- Target SDK `35`
- Java/Kotlin target `17`
- Jetpack Compose
- Room
- Hilt

## Issues Found During Initial Bring-Up

The codebase was not immediately buildable on this laptop for these reasons:

1. The repo did not include a Gradle wrapper.
2. The repo did not include a `local.properties` file pointing to the Android SDK on this machine.
3. The repo did not include `gradle.properties` entries required for AndroidX and stable Gradle memory settings.
4. The manifest referenced launcher icons that were missing from the repository.

## Fixes Applied

### Environment/configuration

- Added `local.properties` with:
  - `sdk.dir=C\:\\Users\\myuva\\AppData\\Local\\Android\\Sdk`
- Added `gradle.properties` with:
  - `android.useAndroidX=true`
  - `android.suppressUnsupportedCompileSdk=35`
  - `org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8`

### App resources

- Updated `app/src/main/AndroidManifest.xml`
  - changed `android:icon` from `@mipmap/ic_launcher` to `@drawable/ic_launcher`
  - changed `android:roundIcon` from `@mipmap/ic_launcher_round` to `@drawable/ic_launcher`
- Added `app/src/main/res/drawable/ic_launcher.xml`

## Build Result

Debug build completed successfully on `2026-05-27`.

APK output:

- `app/build/outputs/apk/debug/app-debug.apk`

## Local Machine Paths Used

- Project root:
  - `C:\Users\myuva\Documents\MakuluApp\MakuluApp`
- Android SDK:
  - `C:\Users\myuva\AppData\Local\Android\Sdk`
- Java runtime used for build:
  - `C:\Program Files\Android\Android Studio\jbr`

## Build Steps Used

### One-time local setup

1. Ensure Android Studio is installed.
2. Ensure the Android SDK exists at:
   - `C:\Users\myuva\AppData\Local\Android\Sdk`
3. Ensure `local.properties` exists in the repo root and points to the SDK:

```properties
sdk.dir=C\:\\Users\\myuva\\AppData\\Local\\Android\\Sdk
```

### Build command

From the project root:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
```

### Output

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Notes For Future Builds

- `local.properties` is machine-specific. Update it if the SDK path differs on another laptop.
- The project currently builds as a debug APK. Release builds may need separate verification because release minification is enabled.
- AGP `8.5.2` warns that `compileSdk = 35` is newer than the tested range. The build still succeeded.

## Recommended Next Checks

- Install the debug APK on a physical device and test:
  - biometric entry
  - printer pairing flow
  - order placement
  - admin PIN flow
  - CSV export paths
- If release APKs are needed, run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleRelease
```
