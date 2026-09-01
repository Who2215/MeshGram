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
  "changelog": "Improved file and voice delivery",
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

## Release signing

Use the properties from `release-signing.properties.example` from a protected
file outside Git. Keep the keystore and private key on `H:` or in a password
manager. Losing the signing key means existing Android installs cannot be
updated in place.

Before publishing, record the SHA-256 of the APK and signing certificate, sign
the canonical manifest payload, and verify the update on a clean test device.

## Telegram news channel

The project can link to a public news channel, but channel creation and posting
must be performed by the account owner in Telegram. Do not place a Telegram
session token or password in source code. Add the final public channel URL to
the app only after the channel exists and its moderation policy is ready.

