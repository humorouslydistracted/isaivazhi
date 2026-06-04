# Easier path: manual upload (no Step 3 API)

This is **recommended** if Play API setup feels too hard.

## What you need

1. **Upload keystore** (one file for all three apps) — see below
2. **Signed `.aab` files** — the agent builds these when keystore secrets are set
3. **Play Console** in your browser — you upload each file yourself

## 1. Create upload keystore (once, on your PC)

**Windows (PowerShell or Command Prompt):**

```bat
keytool -genkey -v -keystore upload-keystore.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Answer the questions (name, country, etc.). Remember the **passwords**.

**Back up** `upload-keystore.jks` somewhere safe (USB, cloud drive you trust).

## 2. Add secrets in Cursor (so the agent can sign builds)

| Secret | Value |
|--------|--------|
| `ANDROID_KEYSTORE_BASE64` | Base64 of the whole `.jks` file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | `upload` |
| `ANDROID_KEY_PASSWORD` | Key password (often same as keystore) |

**Windows — create base64:**

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload-keystore.jks")) | Set-Clipboard
```

Then paste into the secret (one long line).

You do **not** need `PLAY_SERVICE_ACCOUNT_JSON` for manual upload.

## 3. Ask the agent to build

Say: **“keystore secrets added — build release AABs for all three apps.”**

## 4. Upload in Play Console (per app, ~5 minutes each)

1. Open https://play.google.com/console → select the app (e.g. Selavu)
2. Left menu: **Test and release** → **Testing** → **Internal testing**
3. **Create new release** (or **Edit release**)
4. **Upload** → choose `app-release.aab` from the agent
5. **Next** → **Save** → **Review release** → **Start rollout to Internal testing**
6. **Testers** tab → add your Gmail → copy the **opt-in link** → open on your phone

Repeat for Makulu and IsaiVazhi.

On first upload, accept **Google Play App Signing** when asked.

## Package names (must match Console)

| App | Package |
|-----|---------|
| IsaiVazhi | `com.isaivazhi.app` |
| Makulu | `com.makulu.app` |
| Selavu | `com.selavu.app` |
