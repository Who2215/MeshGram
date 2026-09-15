# MeshGram sticker roadmap

## Current offline collection

- 14 original animated Neon Bots with permanent wire IDs.
- 12 transparent Microsoft Fluent Emoji stickers under the MIT license.
- 220 bundled Google Noto Emoji Lottie animations under CC BY 4.0.
- 576 categorized Unicode emoji with recents and category-specific motion.
- Every third-party bundled asset keeps its source URL, attribution, license, and SHA-256.

## Next: unlimited signed packs

1. Define a versioned pack manifest with permanent sticker IDs, content type, size, SHA-256, attribution, and license URL.
2. Sign each manifest with the MeshGram release identity and verify it before extraction.
3. Enforce per-file and expanded-size limits, safe filenames, animation complexity limits, and atomic installation.
4. Cache packs locally and keep a static preview so a damaged animation never breaks a chat.
5. Transfer the pack ID and version in messages; offer the matching signed pack when a peer does not have it.
6. Keep bundled stickers working offline and never execute code from a downloaded pack.

## Telegram-compatible import

- Telegram TGS is gzip-compressed Lottie JSON; Telegram video stickers use VP9 WebM.
- Import only packs made by the user or packs with an explicit redistribution license.
- Never embed a Telegram bot token or user session in the Android APK.
- Validate dimensions, duration, frame rate, transparency, decompressed size, and Lottie features before preview.
- Imported personal packs are local until the author explicitly publishes a signed MeshGram pack.
- A public Telegram link is not proof of redistribution rights.

## Release gates

- Unit tests for IDs, resources, licenses, checksums, recents, and legacy messages.
- Instrumented parse and multi-frame render test for every bundled Lottie asset.
- Redmi and Samsung tests for scrolling, long-press preview, send, replay, background pause, reduced motion, and memory.
- Signed release install over existing user data before publication.
