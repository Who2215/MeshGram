"""Verify both published update feeds against the app's pinned public key."""

import base64
import hashlib
import json
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from sign_release_manifest import canonical_payload


def main():
    root = Path(__file__).resolve().parents[1]
    properties = dict(
        line.split("=", 1) for line in (root / "gradle.properties").read_text().splitlines()
        if line and not line.startswith("#") and "=" in line
    )
    key = serialization.load_der_public_key(
        base64.b64decode(properties["MESHGRAM_RELEASE_PUBLIC_KEY_BASE64"])
    )
    feeds = []
    for name in ("release.json", "release-legacy.json"):
        manifest = json.loads((root / "site" / name).read_text(encoding="utf-8-sig"))
        key.verify(base64.b64decode(manifest["manifestSignature"]),
                   canonical_payload(manifest).encode(), ec.ECDSA(hashes.SHA256()))
        apk = (root / "site" / manifest["file"]).resolve()
        if not apk.is_relative_to((root / "site" / "downloads").resolve()):
            raise ValueError("APK path leaves downloads directory")
        data = apk.read_bytes()
        if hashlib.sha256(data).hexdigest() != manifest["apkSha256"]:
            raise ValueError(f"{name}: APK digest mismatch")
        if len(data) != manifest["sizeBytes"]:
            raise ValueError(f"{name}: APK size mismatch")
        if manifest["packageName"] != "com.meshchat.app":
            raise ValueError(f"{name}: unexpected package")
        feeds.append(manifest)
        print(f'{name}: signature, SHA-256 and size verified (v{manifest["versionCode"]})')
    # A legacy-signed APK may intentionally lag behind the current release
    # while it remains available as a safe fallback for older installations.
    if feeds[1]["versionCode"] > feeds[0]["versionCode"]:
        raise ValueError("Compatibility feed must not be newer than the release")
    for source, target in (("legacyFile", "file"), ("legacySha256", "apkSha256"),
                           ("legacySigningCertificateSha256", "signingCertificateSha256")):
        if feeds[0][source] != feeds[1][target]:
            raise ValueError(f"Compatibility link mismatch: {source}")


if __name__ == "__main__":
    main()
