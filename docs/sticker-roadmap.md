# MeshGram sticker roadmap

## Current offline collection

- 14 original animated Neon Bots with permanent wire IDs.
- 12 transparent Microsoft Fluent Emoji stickers under the MIT license.
- 220 bundled Google Noto Emoji Lottie animations under CC BY 4.0.
- 60 signed Google Noto animations in three live packs: Reactions, Animals, and Food.
- 576 categorized Unicode emoji with recents and category-specific motion.
- Every third-party bundled asset keeps its source URL, attribution, license, and SHA-256.

## Signed-pack foundation

- Implemented: versioned manifests with permanent IDs, content type, byte size, SHA-256, attribution, and license URL.
- Implemented: EC signatures verified against the release public key already pinned in the app.
- Implemented: per-file and pack limits, safe derived filenames, Lottie complexity checks, and atomic internal installation.
- Implemented: a publication tool that computes hashes from reviewed local files and signs the canonical manifest.
- Implemented: a pinned HTTPS index, background downloader, verified installed-pack registry, fallback previews, and pack UI.
- Implemented: a reproducible Noto publisher that rejects duplicates, unsafe Lottie complexity, invalid previews, and unsigned output.
- Next: transfer pack ID/version in messages and offer only the matching signed pack when it is missing.

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
