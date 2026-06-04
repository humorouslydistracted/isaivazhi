# Cursor Cloud development notes

See parent workspace instructions. This file documents cloud-agent setup for all three Android repos in this workspace.

## Environment variables

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

## Per-repo commands

| Repo | Build | Tests | Lint |
|------|-------|-------|------|
| isaivazhi | `./gradlew :app:assembleDebug` | `./gradlew :app:testDebugUnitTest` | `./gradlew :app:lintDebug` (known pre-existing errors) |
| makulu | `./gradlew assembleDebug` | none | `./gradlew lint` |
| selavu | see selavu workaround below | none | `./gradlew lint` |

## Selavu Linux workaround

`selavu/gradlew` is broken under `sh`; `gradle.properties` uses invalid `-XX:MaxPermSize` on Java 17:

```bash
"$JAVA_HOME/bin/java" -Dorg.gradle.jvmargs="-Xmx2048m" \
  -classpath gradle/wrapper/gradle-wrapper.jar \
  org.gradle.wrapper.GradleWrapperMain assembleDebug
```

## Emulator

No KVM on default cloud VMs — use physical device for UI E2E.
