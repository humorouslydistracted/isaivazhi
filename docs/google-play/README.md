# Google Play publishing (Makulu)

Build release bundle: `./gradlew bundleRelease` (use wrapper JAR on Linux for selavu if needed).

Copy `keystore.properties.example` → `keystore.properties`, create upload keystore, enable Play App Signing.

Package: see `app/build.gradle.kts` `applicationId`.

See `PLAY_SECRETS.md` for automated upload credentials.
