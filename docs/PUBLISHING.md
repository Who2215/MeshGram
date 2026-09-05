# MeshGram Publishing

This project keeps release distribution explicit and verifiable. Never put a
YooKassa secret, GitHub token, keystore password, or private signing key in the
repository or in the APK.

## Donations

Create a public YooKassa payment link or checkout URL in the YooKassa
dashboard. Put only that HTTPS URL in the local Gradle properties file:

```properties
MESHGRAM_DONATION_URL=https://yoomoney.ru/to/your-public-link
```

The app renders the URL as a donation action and QR code. It does not receive
or store card details and it does not need access to the YooKassa account.

## GitHub updates

Publish a GitHub Release with an APK asset and a small JSON manifest. The APK
must be signed by the same certificate as the installed release. The manifest
is signed separately with the release ECDSA private key.

Example manifest shape:

```json
{
  "schemaVersion": 1,
  "packageName": "com.meshchat.app",
  "versionCode": 97,
  "versionName": "0.97.0",
  "changelog": ["Improved file and voice delivery"],
  "apkUrl": "https://github.com/OWNER/REPO/releases/download/v0.97.0/MeshGram.apk",
  "apkSha256": "64-lowercase-hex-digest",
  "signingCertificateSha256": "64-lowercase-hex-digest",
  "manifestSignature": "base64-ecdsa-signature"
}
```

Configure the app build with:

```properties
MESHGRAM_UPDATE_MANIFEST_URL=https://raw.githubusercontent.com/OWNER/REPO/main/releases/manifest.json
MESHGRAM_RELEASE_PUBLIC_KEY_BASE64=base64-x509-ecdsa-public-key
```

The app checks periodically when Android allows background work. It accepts
only HTTPS, validates the manifest signature, package name, version, APK hash,
and APK signing certificate, then opens Android's normal user-confirmed
installer. Android does not allow a regular sideloaded app to silently install
its own update; Google Play is the correct route for managed automatic updates.

### Automated GitHub release flow

The public site reads `site/release.json`, so every push to `main` updates the
landing page after the Pages workflow completes. Generate the manifest from the
new APK instead of editing its hash or signature by hand:

```powershell
python tools/sign_release_manifest.py `
  --apk H:\mesh-workspace\share\MeshGram-v108-hybrid.apk `
  --private-key H:\mesh-workspace\release-signing\manifest-signing-key.pem `
  --output site\release.json `
  --version-code 108 `
  --version-name 1.0.8 `
  --apk-url https://github.com/Who2215/MeshGram/raw/refs/heads/main/site/downloads/MeshGram-v108-hybrid.apk `
  --signing-certificate-sha256 <APK-certificate-sha256> `
  --changelog "Describe the first change" `
  --changelog "Describe the second change"
```

Commit the APK, `site/release.json`, `site/downloads/SHA256SUMS.txt`, and the
site files to `main`. The Android updater checks the manifest in the
background and also exposes a manual check in Settings. Installation remains
user-approved by Android; a regular sideloaded app cannot silently replace
itself.

## Release signing

Use the properties from `release-signing.properties.example` from a protected
file outside Git. Keep the keystore and private key on `H:` or in a password
manager. Losing the signing key means existing Android installs cannot be
updated in place.

Before publishing, record the SHA-256 of the APK and signing certificate, sign
the canonical manifest payload, and verify the update on a clean test device.

## Telegram news channel

The public channel is `https://t.me/MeshGram`. For unattended release notices,
use a dedicated Telegram bot as a channel administrator. Never place a Telegram
session token, bot token, password, or login code in source control.

The local publisher is `tools/telegram_release_publisher.py`. It reads the bot
token from `H:\mesh-workspace\secrets\telegram_bot_token.txt`, fetches the
HTTPS release manifest, validates the package and links, and publishes only
versions newer than its local state file. The state is stored outside Git at
`H:\mesh-workspace\secrets\telegram_release_state.json`.

Initialize it at the release already announced manually, without sending a
duplicate post:

```powershell
python tools\telegram_release_publisher.py publish --initialize
```

Run one unattended check with:

```powershell
python tools\telegram_release_publisher.py publish
```

The same publisher can update the channel avatar without exposing the token:

```powershell
python tools\telegram_release_publisher.py avatar
```

### GitHub Actions automation

GitHub Actions can run the release publisher and bot command worker for free
on the public repository. Add the bot token once at **Settings -> Secrets and
variables -> Actions -> New repository secret**:

- Name: `TELEGRAM_BOT_TOKEN`
- Value: the contents of the local token file

The token is read only as the `TELEGRAM_BOT_TOKEN` Actions secret. It is not
written to the repository. The `telegram-release` workflow runs when a GitHub
Release is published and posts the manifest changelog to `@MeshGram`. The
`telegram-bot` workflow runs every five minutes and handles `/latest`,
`/download`, `/status`, `/support`, `/report`, and `/help` in private chats.
The workflows can also be started manually from the Actions tab.

The first Actions run for the current release is intentionally a no-op because
`.github/telegram-release-state.json` starts at the already announced version.
Future releases are announced when their `versionCode` is greater than that
state. The bot update cursor is stored in `.github/telegram-bot-offset.json` so
scheduled runs do not answer the same update twice.

GitHub scheduled workflows are not real-time services: a command may wait for
the next scheduled run and GitHub may delay scheduled jobs during load. The
worker can still be run locally for near-real-time development testing while
the PC is on.

Keep the bot restricted to posting, editing/deleting its own posts, pinning,
and changing channel information. Do not grant it the ability to add
administrators. A separate scheduler should run the publisher on the host that
stores the token; a powered-off host cannot publish updates.
