# MeshGram website

Static landing page for GitHub Pages or Cloudflare Pages.

## Publish

1. Put the contents of `site/` into a public GitHub repository and enable GitHub Pages, or deploy the folder as a static Cloudflare Pages project.
2. Keep the APK in `downloads/MeshGram-v107-hybrid.apk` and publish `downloads/SHA256SUMS.txt` alongside it.
3. Replace the pending Telegram channel link in `index.html` only after the official channel exists.

## Release safety

The Android update checker accepts only HTTPS manifests with a valid ECDSA signature,
the expected package name, APK SHA-256, and signing certificate digest. Configure the
manifest URL and release public key in `gradle.properties` only when a project-owned
keystore and signing key are available. The current APK is a debug test build and is
not a production ownership signature.

The site contains no analytics, third-party scripts, forms, or external fonts. `_headers` adds a restrictive CSP on hosts that support it.
