# Step 3: Play API access (optional)

**You only need this if a bot uploads `.aab` files for you.**

If you prefer to **drag-and-drop** the app file in Play Console (easier), skip to
[MANUAL_UPLOAD.md](./MANUAL_UPLOAD.md) and only create the upload keystore (Step 4).

---

## Part A — Play Console (use the same Google account as your developer account)

1. Open https://play.google.com/console
2. Click **Setup** (gear icon, left sidebar) → **API access**
3. Under **Linked projects**, click **Link a Google Cloud project**
   - Choose **Create new project** → name it e.g. `play-upload` → **Link**
4. After linking, find **Service accounts** on the same page
5. Click **Create new service account** (opens Google Cloud in a new tab)

## Part B — Google Cloud (new tab)

6. Click **+ Create service account**
   - Name: `play-publisher`
   - Click **Create and continue** → **Done** (skip optional roles here)
7. In the list, click the new account → tab **Keys** → **Add key** → **Create new key**
   - Type: **JSON** → **Create**
   - A file downloads (e.g. `play-upload-xxxxx.json`). **Keep it private.**

## Part C — Back to Play Console

8. Return to the **API access** page (Play Console tab)
9. Click **Grant access** (or **Manage permissions**) for the service account you created
10. **App permissions:** add all three apps (IsaiVazhi, Makulu, Selavu)
11. **Account permissions:** check **Release to testing tracks** (or **Admin (all permissions)** for simplicity)
12. Click **Invite user** / **Apply** / **Save**

## Part D — Give the JSON to Cursor (Path B automation)

13. Open the downloaded `.json` file in a text editor
14. Copy **the entire file** (starts with `{`, ends with `}`)
15. Paste into Cursor secret **`PLAY_SERVICE_ACCOUNT_JSON`** (single secret, multiline is OK)

Also add keystore secrets — see `PLAY_SECRETS.md` and `MANUAL_UPLOAD.md`.

---

## Troubleshooting

| Problem | Fix |
|--------|-----|
| No **API access** in Setup | Finish developer account verification first |
| **403** on upload | Service account not granted access to that app |
| Wrong package | App in Console must match `applicationId` exactly |
