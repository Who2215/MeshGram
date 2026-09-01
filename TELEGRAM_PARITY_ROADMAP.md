# Telegram Parity Roadmap (MeshGram)

This project follows a **clean-room parity** approach:
- we do not copy Telegram proprietary source/assets directly;
- we reproduce equivalent user-facing functionality on the Mesh transport.

## Extracted APK Profile (`Telegram+12.6.4+(Android6,universal).apk`)

Static signals captured from manifest/package analysis:
- `package`: `org.telegram.messenger`
- `compileSdk`: 35
- components:
  - activities: 26
  - services: 29
  - receivers: 26
  - providers: 6
  - permissions declared: 71
- major modules present:
  - launch/deep-link/share flows
  - notifications and quick reply
  - contact sync/authenticator
  - media upload/encoding/player
  - stories upload
  - live location sharing
  - VoIP/call stack
  - widgets
  - billing/premium
  - maps/location integrations

## Current MeshGram Status

Implemented:
- BLE mesh transport with relay/store-and-forward
- Wi-Fi Direct offline bootstrap (auto-discovery + auto-invite)
- directed UDP fallback for Wi-Fi Direct/LAN peers
- BLE-only policy build (Wi-Fi/internet permissions removed; app-service BLE scan only)
- BLE courier store-and-forward cache for encrypted message frames
- v42 media composer: photo/video/audio/document picker tray, video-note capture entry point
- v42 appearance presets: chat/list gradients, themed bubbles, composer and accent colors
- v43 in-app attachment preview dialog with media/file metadata and Open/Share actions
- v44 media gallery filters for All / Photos / Videos / Voice / Files
- v45 in-app audio playback for voice messages and inline video-note preview/player
- v46 Android Share target: share text/files into MeshGram and choose the mesh chat recipient
- v47 mesh hardening: strict hop-envelope validation and stronger seen-frame cache behavior
- v48 at-rest hardening: keystore AES-GCM fallback (no plaintext file writes when EncryptedFile fails)
- v49 privacy hardening: backup/device-transfer disabled + transient decrypted cache auto-prune/delete + FLAG_SECURE anti-screenshot
- v50 app lock hardening: local PIN lock (encrypted prefs), auto-lock on app background, settings for enable/change/disable PIN
- v51 anti-bruteforce: PIN attempt limits with progressive cooldown lockout and unlock countdown UI
- v52 Saved Messages: encrypted local self-chat for private notes and files, including forwarding, edits, deletion, reactions, and pins without mesh transmission
- v53 Saved Messages 2.0: encrypted local tags, tag-aware search, quick tag filters and tag labels in message bubbles
- v54 rich message text: bold, italic, underline, strikethrough, monospace, labeled/plain links, link confirmation, composer help and parser unit tests
- v55 scheduled messages: encrypted persistent schedule queue, restart recovery, stable deduplication IDs, direct/group/channel/Saved dispatch, retry, in-chat list and cancellation
- v56 voice UX: live recording amplitude waveform, explicit cancel/send controls, inline encrypted playback, seeking, progress waveform and 1x/1.5x/2x playback speeds
- v57 media privacy storage: temporary decrypted-cache size visibility, manual scoped cleanup and explicit protection of encrypted history/attachments
- v58 resumable file visibility: per-recipient ACK progress, encrypted queue recovery, missing-chunk retry and explicit transfer-stop controls
- v59 receive progress: live incoming chunk assembly, sender-aware progress cards and ACK-manifest requests for missing encrypted chunks
- v60 crash-safe receiving: encrypted partial-chunk vault, bounded restore/TTL, exact ACK reconciliation and full retry after decode, SHA-256 or secure-storage failure
- v61 voice recording controls: Android 7+ pause/resume, pause-aware duration, frozen waveform state and Android 6-compatible fallback
- v62 video-note draft flow: bounded system-camera capture, circular looping preview, explicit send/retake/cancel and deterministic temporary-file cleanup
- v63 media albums and captions: multi-select up to 10 items, album metadata, formatted captions and independently resumable encrypted transfers
- E2E encrypted direct/group text
- group chats
- encrypted file transfer with chunking + ACK/resend
- persistent outgoing file queue with resume after app restart
- encrypted compressed local storage
- portable encrypted backup/import
- onboarding screen
- recipient identity persistence (Node ID/fingerprint)
- file open from chat attachment tap

## Product Principles

- Offline-first: messaging must keep working without internet or a SIM card.
- Addressed delivery: only the intended app identity can decrypt a direct message.
- Store-and-forward: participating MeshGram devices may relay opaque encrypted frames.
- Clean-room parity: reproduce useful messenger behavior without copying Telegram code, assets, or branding.
- Android 6+: every release must preserve API 23 compatibility and scale to current Android versions.
- Honest security: cryptographic claims require tests and an external review before a production release.

## Full Delivery Plan

### 1. Messaging Core

- Saved Messages 2.0: tags, source grouping, filters and saved-media view.
- search filters by sender, date, media kind, tag and conversation.
- drafts, unread counters, archive, mute, pin and bulk actions.
- rich text entities, links, mentions, hashtags and code formatting.
- scheduled messages, silent-send semantics and local reminders.
- optional disappearing messages with explicit retention policy.

### 2. Media

- camera capture, photo/video albums and captions.
- robust voice notes with waveform, pause/resume and seeking.
- circular video messages with recording progress and preview.
- attachment download progress, retry/cancel and resumable chunks.
- cache limits, automatic cleanup and encrypted media index.

### 3. Groups, Channels And Communities

- owners, admins, moderators and granular permissions.
- invite QR/codes with revocation and expiration.
- group topics with separate history, unread state and media.
- polls, checklists, mentions, moderation log and anti-spam controls.
- broadcast channels, reactions and optional discussion group.

### 4. BLE Mesh Transport

- stable neighbor discovery and deterministic direct recipient routing.
- encrypted store-and-forward queues, frame deduplication and TTL limits.
- delivery, relay and file-chunk acknowledgements with bounded retries.
- adaptive chunk size, congestion control and fair queue scheduling.
- battery-aware scan/advertise windows and Android background recovery.
- protocol version negotiation, migrations and backward compatibility.
- diagnostics for hops, latency, queue age and delivery reason codes.

### 5. Security And Device Ownership

- audited identity key lifecycle and explicit contact fingerprint verification.
- session-key rotation and forward-secrecy design review.
- encrypted local database and attachments protected by Android Keystore.
- app lock, biometric unlock option, anti-bruteforce and privacy screen.
- encrypted device transfer with old-device approval and recovery code.
- signed updates, dependency scanning, reproducible builds and threat model.

### 6. Android Platform Quality

- foreground/background service reliability and actionable notifications.
- quick reply, share target, deep links and launcher shortcuts.
- accessibility, localization, tablet layouts and dynamic font scaling.
- theme editor for chat background, bubbles, typography and accent colors.
- crash-safe migrations and import/export compatibility tests.

### 7. Verification And Release

- unit tests for routing, crypto envelopes, deduplication, TTL and storage.
- integration tests for reconnect, relay, duplicate frames and corrupt data.
- two-device and three-device BLE tests for text, media and multi-hop queues.
- Android 6/API 23 emulator test plus current Samsung/Huawei physical-device tests.
- signed release APK/AAB, changelog, checksums and rollback package.
- independent cryptographic/security audit before claims of production-grade security.

### 8. Later Platforms

- extract protocol and crypto test vectors into a platform-neutral specification.
- build an iOS client after Android transport and storage formats stabilize.
- evaluate desktop companion support without weakening offline identity guarantees.
