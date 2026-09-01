# MeshGram release security

## Author signing

Release signing is intentionally disabled until the owner's keystore is supplied. Build with the four `MESHGRAM_*` Gradle properties from `release-signing.properties.example`. Keep the `.jks` and passwords outside the repository, preferably under an encrypted folder on `H:` and in a password manager.

The APK identity is the signing certificate, not a text label inside the app. Anyone who receives the signed APK can inspect that certificate, so publish its SHA-256 fingerprint with every release.

Example keystore creation:

```text
keytool -genkeypair -v -keystore H:\\mesh-workspace\\keys\\meshgram-release.jks -alias meshgram-release -keyalg EC -groupname secp256r1 -validity 10000
```

## Update contract

`MeshReleaseManifest` is the format for an explicit update check. A production manifest must contain:

- an HTTPS APK URL;
- the APK SHA-256;
- the expected APK signing-certificate SHA-256;
- a detached ECDSA signature over the canonical manifest payload;
- a human-readable changelog.

The Android client schedules a device-idle background check only when both
`MESHGRAM_UPDATE_MANIFEST_URL` and `MESHGRAM_RELEASE_PUBLIC_KEY_BASE64` are configured.
It limits response sizes, allows only up to three HTTPS-to-HTTPS redirects, verifies the package name,
APK hash and signing certificate, then opens the system installer only after the
user taps the update notification.

The client must verify the manifest signature, download hash, package name, and APK certificate before opening Android's user-approved package installer. It must never silently install, downgrade, or accept an HTTP/unknown-certificate APK.

Google Drive can host a release file, but a private folder is not an update API and Drive sharing links can change. For production, use Play Console or a stable HTTPS release host. A Drive-based flow still needs a public download URL or OAuth and always requires Android's confirmation dialog.

## Donations

Do not embed a card number in the application. Configure one official SBP/checkout URL and a QR generated from that URL, publish it in the release notes, and use a verified merchant/recipient account. The app should show the exact recipient name before opening the payment flow.

## Security boundary

BLE relays can forward opaque MeshGram frames only when MeshGram is installed and running on the relay phone. Ordinary Bluetooth devices cannot relay this app's packets. E2E encryption protects content from relays, but it does not guarantee delivery, anonymity, or immunity from a compromised endpoint; an independent cryptographic review remains required before production security claims.
