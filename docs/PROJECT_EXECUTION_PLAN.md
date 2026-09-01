# MeshGram execution plan

## Completed in the current cycle

- Android client uses BLE as the preferred route and the internet relay only as an app-scoped fallback.
- Relay input is bounded by payload size, envelope size, client count, Base64 validation, and per-client rate limits.
- Incoming attachment names and transfer identifiers are sanitized and checked against the attachment root.
- The app rejects remote `ws://` relay URLs and allows cleartext WebSocket only for local/private hosts; public relays must use `wss://`.
- Static site includes a live starfield, responsive layout, current APK, SHA-256 checksum, restrictive headers, and a release manifest.
- Cloudflare Durable Object relay scaffold passes `wrangler deploy --dry-run` and is ready for owner-authenticated deployment.
- `1.0.6` debug APK passes Kotlin unit tests and Android lint, then starts on Samsung S24 FE and Redmi Note 10 Pro.
- Security audit is stored under `docs/security/2026-09-01-standard-scan/`.

## Required before public launch

1. Deploy the Worker from `relay/cloudflare-worker/` after the owner signs in with `npx wrangler login`.
2. Add relay admission authentication: a short-lived access token or proof-of-possession handshake. The current relay is intentionally opaque but not account-authenticated.
3. Configure a stable `wss://` relay URL in the Android release build and test two distant devices through it.
4. Create an owner-controlled release keystore, build a signed release APK, publish its certificate fingerprint, and generate a signed update manifest.
5. Publish `site/` on GitHub Pages or Cloudflare Pages. Replace the pending Telegram link only after the official channel is created by the owner.
6. Run external checks after deployment: TLS configuration, WebSocket admission, rate limits, APK checksum, signed update rejection, and Android install confirmation.

## Product roadmap

- Add authenticated relay sessions and per-recipient routing at the server edge.
- Add resumable encrypted file transfer through the relay with per-account quotas.
- Add automated release builds and signed manifest generation from the owner-controlled repository.
- Add crash reporting only if explicitly enabled by the user; default privacy remains analytics-free.
- Keep BLE transport opt-in and app-scoped. Ordinary Bluetooth devices cannot be used as MeshGram relays.
