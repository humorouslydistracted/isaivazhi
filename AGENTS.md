# AGENTS.md

## Cursor Cloud specific instructions

This workspace contains **three independent Android apps** (`isaivazhi`, `makulu`, `selavu`). There is no backend or shared monorepo build.

### Environment variables

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

The VM update script writes `local.properties` (`sdk.dir`) in each repo when `ANDROID_HOME` is set.

### Build / test / lint

| Repo | Build | Tests | Lint |
|------|-------|-------|------|
| isaivazhi | `./gradlew :app:assembleDebug` | `./gradlew :app:testDebugUnitTest` | `./gradlew :app:lintDebug` (pre-existing errors) |
| makulu | `./gradlew assembleDebug` | — | `./gradlew lint` |
| selavu | wrapper JAR workaround (below) | — | same workaround + `lint` |

**isaivazhi:** NDK/CMake; `fetchSqliteVec` needs network on first build.

### Selavu on Linux

`selavu/gradlew` fails under `sh`; `gradle.properties` has invalid `-XX:MaxPermSize` for Java 17:

```bash
cd ../selavu
"$JAVA_HOME/bin/java" -Dorg.gradle.jvmargs="-Xmx2048m" \
  -classpath gradle/wrapper/gradle-wrapper.jar \
  org.gradle.wrapper.GradleWrapperMain assembleDebug
```

### Emulator

Cloud VMs here have **no KVM** — emulators may not finish booting. Use APK builds + isaivazhi unit tests on VM; physical device for UI E2E.
