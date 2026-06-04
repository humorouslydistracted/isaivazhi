# Secrets for automated Play upload

Configure these in **Cursor Cloud secrets** (or CI) — never commit values.

| Secret | Purpose |
|--------|---------|
| `PLAY_SERVICE_ACCOUNT_JSON` | Full JSON key for a Play Console API service account |
| `PLAY_PACKAGE_NAME` | e.g. `com.isaivazhi.app` (must match `applicationId`) |
| `ANDROID_KEYSTORE_BASE64` | Base64 of `upload-keystore.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | e.g. `upload` |
| `ANDROID_KEY_PASSWORD` | Key password |

Without these, builds still produce a signed `.aab` locally; a human uploads in Play Console.
