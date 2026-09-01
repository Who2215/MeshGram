# Mesh Messenger (Android, E2E MVP)

Hybrid messenger over BLE mesh with an optional internet relay and strong end-to-end encryption.

## Implemented

- BLE mesh transport:
  - discovery by BLE service UUID
  - advertising + scanning (fallback to scan-only/advertise-only)
  - GATT packet transfer with chunk assembly
- Multi-hop relay:
  - frame deduplication (`id`)
  - hop-limited forwarding (`maxHops`)
  - persistent store-and-forward queue for intermittent BLE contacts
  - pure hop policy tests for loop and boundary protection
- Hybrid routing:
  - BLE is preferred whenever a ready MeshGram BLE path exists
  - internet relay is used only when no BLE path is available
  - relay is app-scoped and never changes Wi-Fi or mobile-data settings for other apps
  - unrelated Bluetooth devices are never used as relays
- E2E cryptography:
  - identity key exchange via signed `HELLO` frames
  - per-message ECDH (P-256) + HKDF-SHA256
  - message encryption with AES-256-GCM
  - sender authenticity with ECDSA signatures
  - local keys stored in encrypted preferences
- Updated Compose UI:
  - node alias and fingerprint
  - secure peers chips
  - encrypted chat stream
  - cleaner modern card-based layout
- Release/update safety:
  - background update check is disabled until an HTTPS manifest and trusted public key are configured
  - downloaded APKs are checked for package name, SHA-256 and signing certificate before Android's installer opens
  - no silent sideload installation; Google Play is the recommended automatic update channel

## Compatibility target

- `minSdk = 23` (Android 6.0) up to modern Android versions.

## Build

1. Open project root `mesh-messenger-android`.
2. Ensure `local.properties` points to Android SDK (example: `H:\\Android\\Sdk`).
3. Build with:
   - `gradlew.bat assembleDebug`
4. APK output:
   - `app/build/outputs/apk/debug/app-debug.apk`

## Security notes

- Mesh nodes relay packets but cannot decrypt traffic not addressed to them.
- If a known node suddenly changes key material, message processing is blocked and flagged as suspicious.
- BLE store-and-forward requires MeshGram to be installed and running on relay phones; unrelated Bluetooth devices do not forward application packets.
- Long-distance delivery requires a reachable `wss://` relay endpoint configured in the app. The bundled relay forwards opaque encrypted frames only; it cannot decrypt message contents.
- BLE proximity can only be inferred from active MeshGram BLE links. Android does not provide a generic way to route arbitrary app data through phones that do not run MeshGram.
- For production, add backup/restore strategy, key transparency, and message receipts.
