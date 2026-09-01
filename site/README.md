# MeshGram website

Static landing page for GitHub Pages or Cloudflare Pages.

## Publish

1. Put the contents of `site/` into a public GitHub repository and enable GitHub Pages, or deploy the folder as a static Cloudflare Pages project.
2. Keep the current APK in `downloads/MeshGram-v108-hybrid.apk` and publish `downloads/SHA256SUMS.txt` alongside it.
3. Replace the pending Telegram channel link in `index.html` only after the official channel exists.

The landing page fetches `release.json` on every visit and fills in the current
version, download path, file size, hash, and changelog. Updating that manifest
and pushing to `main` updates the site through the Pages workflow.

## Localization and support

The page detects the browser language on first visit and lets visitors choose a
language from the header. The choice is stored locally so it survives revisits.
Russian, English, Spanish, German, French, Portuguese, Italian, Turkish,
Chinese, Japanese, Korean, Arabic, and Hindi are available; unsupported browser
locales use English as a readable fallback. The creator support button points
to the project's public YooKassa payment page and does not collect credentials
on the site.

## Release safety

The Android update checker accepts only HTTPS manifests with a valid ECDSA signature,
the expected package name, APK SHA-256, and signing certificate digest. Configure the
manifest URL and release public key in `gradle.properties` only when a project-owned
keystore and signing key are available. The current APK is a debug test build and is
not a production ownership signature.

The site contains no analytics, third-party scripts, forms, or external fonts. `_headers` adds a restrictive CSP on hosts that support it.
