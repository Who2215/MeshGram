#!/usr/bin/env python3
"""Create the signed release.json consumed by MeshGram's Android updater."""

import argparse
import base64
import hashlib
import json
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


def canonical_payload(manifest: dict) -> str:
    return "|".join(
        [
            str(manifest["schemaVersion"]),
            manifest["packageName"],
            str(manifest["versionCode"]),
            manifest["versionName"],
            "\n".join(manifest["changelog"]),
            manifest["apkUrl"],
            manifest["apkSha256"].lower(),
            manifest["signingCertificateSha256"].lower(),
        ]
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--private-key", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--apk-url", required=True)
    parser.add_argument("--signing-certificate-sha256", required=True)
    parser.add_argument("--changelog", action="append", required=True)
    parser.add_argument(
        "--file",
        default=None,
        help="Relative download path used by the website, for example downloads/MeshGram-v108-hybrid.apk",
    )
    args = parser.parse_args()

    apk_bytes = args.apk.read_bytes()
    digest = hashlib.sha256(apk_bytes).hexdigest()
    manifest = {
        "schemaVersion": 1,
        "packageName": "com.meshchat.app",
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "changelog": args.changelog,
        "apkUrl": args.apk_url,
        "apkSha256": digest,
        "signingCertificateSha256": args.signing_certificate_sha256.lower(),
    }
    private_key = serialization.load_pem_private_key(
        args.private_key.read_bytes(), password=None
    )
    if not isinstance(private_key, ec.EllipticCurvePrivateKey):
        raise SystemExit("manifest key must be an EC private key")
    signature = private_key.sign(
        canonical_payload(manifest).encode("utf-8"), ec.ECDSA(hashes.SHA256())
    )
    manifest["manifestSignature"] = base64.b64encode(signature).decode("ascii")
    manifest["file"] = args.file or f"downloads/MeshGram-v{args.version_code}-hybrid.apk"
    manifest["sizeBytes"] = len(apk_bytes)
    manifest["releaseType"] = "debug-test-build"
    manifest["notes"] = args.changelog

    args.output.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({"sha256": digest, "sizeBytes": len(apk_bytes)}, indent=2))


if __name__ == "__main__":
    main()
