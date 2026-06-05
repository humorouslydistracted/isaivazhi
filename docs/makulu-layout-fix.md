# Makulu layout fix (apply in makulu repo)

The Cloud Agent GitHub token can **read** `humorouslydistracted/makulu` but **cannot push** to it (403 — app is only installed for `isaivazhi`). Apply this patch from your machine or grant the Cursor GitHub App access to the **makulu** repository.

## Option A — `git am` (recommended)

```bash
git clone https://github.com/humorouslydistracted/makulu.git
cd makulu
git checkout -b cursor/fix-system-bar-layout-e148
curl -fsSL -o layout.patch \
  https://raw.githubusercontent.com/humorouslydistracted/isaivazhi/cursor/fix-system-bar-layout-e148/patches/makulu/0001-Improve-system-bar-insets-on-setup-screens-sync-stat.patch
git am layout.patch
# versionCode 4 — build: ./gradlew :app:bundleRelease
git push -u origin cursor/fix-system-bar-layout-e148
```

## Option B — Grant repo access to Cursor

GitHub → **Settings** → **Applications** → **Cursor** (or installed GitHub App) → **Repository access** → add **makulu** (and **selavu** if needed). Then ask the agent to push again.

## What the patch changes

- `Theme.kt` — status/navigation bar colors via `WindowCompat` (Selavu-style)
- `MainActivity.kt` — `safeDrawingPadding()` on PIN/printer/reset setup screens
- `app/build.gradle.kts` — `versionCode` 4 for Play upload
