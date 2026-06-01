// Top-level build file for the native Kotlin/Compose port of IsaiVazhi.
//
// This project lives parallel to the existing Capacitor app under android/.
// The two apps use different applicationIds so they install side-by-side
// during the migration. All shared native Java + C++ code is COPIED here
// (not symlinked) so this project can be built independently and the
// Capacitor app continues to work unchanged until cutover.

plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.x ships its own Compose Compiler Gradle plugin; the version
    // tracks the Kotlin version, no separate Compose-compiler-extension pin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
