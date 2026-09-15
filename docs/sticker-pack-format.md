# Signed sticker packs

MeshGram dynamic sticker packs are data-only bundles. They never contain executable code and are installed only after the manifest signature, declared sizes, SHA-256 hashes, PNG signatures, and Lottie limits pass locally.

## Authoring

Create a private descriptor with pack metadata and one entry per sticker. Each entry includes `assetFile` and `previewFile` relative to a reviewed asset directory, HTTPS publication URLs, dimensions, duration, and frame rate. Do not publish the descriptor because it contains build-machine paths.

Run:

```powershell
python tools/sign_sticker_pack.py `
  --descriptor H:\private\pack.json `
  --asset-root H:\private\pack-assets `
  --private-key H:\private\meshgram-update-key.pem `
  --output site\stickers\community.fun\1\manifest.json
```

The signer computes byte sizes and SHA-256 values from the local files and strips local paths from the public manifest. It uses the same EC update identity already pinned in the Android application.

## Permanent IDs

- Pack IDs: lowercase ASCII, 3-48 characters, for example `community.fun`.
- Sticker IDs: `<pack-id>/<item-id>`, for example `community.fun/laugh`.
- Never reuse an ID for a different drawing or meaning.
- New artwork requires a new pack version; sent-message IDs remain stable.

## Runtime limits

- Up to 100 stickers and 24 MiB declared data per pack.
- Lottie: up to 512 KiB, 512 x 512, 60 fps, and 5 seconds.
- PNG artwork: up to 2 MiB; PNG preview: up to 256 KiB.
- External Lottie images and URLs, unsafe paths, duplicate IDs, unknown fields, and unsigned manifests are rejected.
- Installation uses derived local filenames and a same-volume staging directory, then commits the verified version atomically.
