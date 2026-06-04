import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(keystorePropertiesFile.inputStream())
    }
}

android {
    namespace = "com.isaivazhi.app"
    compileSdk = 36

    flavorDimensions += "brand"
    productFlavors {
        create("isaivazhi") {
            dimension = "brand"
            applicationId = "com.isaivazhi.app"
            resValue("string", "app_name", "IsaiVazhi")
        }
        create("makulu") {
            dimension = "brand"
            applicationId = "com.makulu.app"
            resValue("string", "app_name", "Makulu")
        }
        create("selavu") {
            dimension = "brand"
            applicationId = "com.selavu.app"
            resValue("string", "app_name", "Selavu")
        }
    }

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2-kt"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++14"
            }
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // Extract native libs to nativeLibraryDir at install time so
        // EmbeddingDb's File.exists() probe for libsqlite_vec.so succeeds.
        // SDK 36 defaults to useLegacyPackaging=false which keeps .so files
        // inside the APK — that path isn't accessible via File and the probe
        // fell back to NativeAccelerator on every recommender call, causing
        // ~3 s frame stalls from the CursorWindow refill churn in logs.txt
        // after a few minutes of use.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
            )
        }
    }

    // ONNX model + external data must NOT be compressed in the APK; ONNX
    // Runtime opens them via AssetManager.openFd() which requires the asset
    // to be stored uncompressed (compressed assets only support InputStream).
    androidResources {
        noCompress += listOf("onnx", "data")
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // --- Kotlin + Compose ---
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.2.0")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // --- DataStore (replaces Capacitor Preferences) ---
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- Media3 (carried over identical to Capacitor build) ---
    val media3Version = "1.10.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media:media:1.7.0")

    // --- ONNX Runtime (embedding inference, same model as Capacitor build) ---
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.0")

    // --- SQLite + sqlite-vec ---
    implementation("com.github.requery:sqlite-android:3.45.0")

    // --- Drag-to-reorder for the Up Next queue (push #39). Maintained
    // library that integrates with Compose LazyColumn via
    // `rememberReorderableLazyListState` + `ReorderableItem`. ---
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    // --- Debugging ---
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // --- Tests ---
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

// --- sqlite-vec native library fetch ---
//
// Same Gradle task as the Capacitor build's android/app/build.gradle. Downloads
// the official prebuilt libsqlite_vec.so per ABI from the GitHub release at
// build time and drops them into jniLibs/. If download fails, EmbeddingDb
// falls back to NativeAccelerator (NEON SIMD) at runtime.
val sqliteVecVersion = "0.1.6"
val sqliteVecAbiSuffix = mapOf(
    "arm64-v8a" to "android-aarch64",
    "armeabi-v7a" to "android-armv7a",
    "x86_64" to "android-x86_64"
)

tasks.register("fetchSqliteVec") {
    description = "Downloads sqlite-vec prebuilt .so per ABI into jniLibs/"
    group = "build"
    doLast {
        val libName = "libsqlite_vec.so"
        val doneFlag = layout.buildDirectory.file("sqlite-vec-$sqliteVecVersion-fetched.flag").get().asFile
        if (doneFlag.exists()) {
            logger.info("sqlite-vec $sqliteVecVersion already fetched")
            return@doLast
        }
        sqliteVecAbiSuffix.forEach { (abi, suffix) ->
            val jniDir = file("src/main/jniLibs/$abi")
            val soFile = jniDir.resolve(libName)
            if (soFile.exists()) {
                logger.info("sqlite-vec already present for $abi: $soFile")
                return@forEach
            }
            jniDir.mkdirs()
            val url = "https://github.com/asg017/sqlite-vec/releases/download/v$sqliteVecVersion/sqlite-vec-$sqliteVecVersion-loadable-$suffix.tar.gz"
            val tmpDir = layout.buildDirectory.dir("sqlite-vec-tmp-$abi").get().asFile
            tmpDir.mkdirs()
            val tarFile = tmpDir.resolve("sqlite-vec.tar.gz")
            try {
                logger.lifecycle("Fetching sqlite-vec $sqliteVecVersion for $abi: $url")
                ant.withGroovyBuilder {
                    "get"("src" to url, "dest" to tarFile, "verbose" to false, "retries" to 2)
                    "untar"("src" to tarFile, "dest" to tmpDir, "compression" to "gzip")
                }
                var extracted: File? = null
                tmpDir.walk().forEach { f ->
                    if (f.name == "vec0.so" || f.name == "libvec0.so" || f.name == "libsqlite_vec.so") {
                        extracted = f
                    }
                }
                if (extracted == null) {
                    throw GradleException("Could not find .so inside $tarFile")
                }
                extracted!!.copyTo(soFile, overwrite = true)
                logger.lifecycle("sqlite-vec $abi installed at $soFile")
            } catch (e: Exception) {
                logger.warn("sqlite-vec fetch failed for $abi: ${e.message} — runtime will fall back to NativeAccelerator")
            } finally {
                tmpDir.deleteRecursively()
            }
        }
        doneFlag.parentFile.mkdirs()
        doneFlag.writeText("fetched at " + System.currentTimeMillis())
    }
}

tasks.named("preBuild").configure {
    dependsOn("fetchSqliteVec")
}
