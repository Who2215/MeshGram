#!/usr/bin/env python3
"""Build and sign a MeshGram sticker-pack manifest from local reviewed files."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
from urllib.parse import urlparse


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
KINDS = {"LOTTIE", "PNG"}


def canonical_payload(manifest: dict) -> str:
    fields = [
        str(manifest["schemaVersion"]),
        manifest["packId"],
        str(manifest["version"]),
        manifest["title"],
        manifest["attribution"],
        manifest["licenseUrl"],
        str(len(manifest["stickers"])),
    ]
    for sticker in manifest["stickers"]:
        fields.extend(
            [
                sticker["id"],
                sticker["label"],
                sticker["category"],
                sticker["kind"],
                sticker["assetUrl"],
                sticker["previewUrl"],
                sticker["assetSha256"].lower(),
                sticker["previewSha256"].lower(),
                str(sticker["assetBytes"]),
                str(sticker["previewBytes"]),
                str(sticker["width"]),
                str(sticker["height"]),
                str(sticker.get("durationMs", 0)),
                str(sticker.get("frameRate", 0)),
            ]
        )
    return "".join(f"{len(value.encode('utf-8'))}:{value}" for value in fields)


def safe_file(root: Path, relative_path: str) -> Path:
    candidate = (root / relative_path).resolve(strict=True)
    if root != candidate and root not in candidate.parents:
        raise ValueError(f"file escapes asset root: {relative_path}")
    if not candidate.is_file():
        raise ValueError(f"not a file: {relative_path}")
    return candidate


def require_https(value: str, field: str) -> None:
    parsed = urlparse(value)
    if parsed.scheme.lower() != "https" or not parsed.hostname or parsed.username or parsed.fragment:
        raise ValueError(f"{field} must be an HTTPS URL without credentials or fragment")


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def build_manifest(descriptor: dict, asset_root: Path) -> dict:
    manifest = {
        "schemaVersion": 1,
        "packId": descriptor["packId"],
        "version": int(descriptor["version"]),
        "title": descriptor["title"],
        "attribution": descriptor["attribution"],
        "licenseUrl": descriptor["licenseUrl"],
        "stickers": [],
    }
    require_https(manifest["licenseUrl"], "licenseUrl")
    for source in descriptor["stickers"]:
        if source["kind"] not in KINDS:
            raise ValueError(f"unsupported kind: {source['kind']}")
        require_https(source["assetUrl"], "assetUrl")
        require_https(source["previewUrl"], "previewUrl")
        asset = safe_file(asset_root, source["assetFile"])
        preview = safe_file(asset_root, source["previewFile"])
        if preview.read_bytes()[:8] != PNG_SIGNATURE:
            raise ValueError(f"preview is not PNG: {source['previewFile']}")
        if source["kind"] == "PNG" and asset.read_bytes()[:8] != PNG_SIGNATURE:
            raise ValueError(f"sticker is not PNG: {source['assetFile']}")
        if source["kind"] == "LOTTIE":
            animation = json.loads(asset.read_text(encoding="utf-8"))
            for field in ("w", "h", "fr", "ip", "op"):
                if field not in animation:
                    raise ValueError(f"Lottie file is missing {field}: {source['assetFile']}")
            if any(item.get("p") for item in animation.get("assets", []) if isinstance(item, dict)):
                raise ValueError(f"external Lottie assets are forbidden: {source['assetFile']}")
        manifest["stickers"].append(
            {
                key: source[key]
                for key in (
                    "id", "label", "category", "kind", "assetUrl", "previewUrl",
                    "width", "height"
                )
            }
            | {
                "durationMs": int(source.get("durationMs", 0)),
                "frameRate": int(source.get("frameRate", 0)),
                "assetSha256": digest(asset),
                "previewSha256": digest(preview),
                "assetBytes": asset.stat().st_size,
                "previewBytes": preview.stat().st_size,
            }
        )
    return manifest


def sign_manifest(manifest: dict, private_key_path: Path, password_env: str) -> dict:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    key_data = private_key_path.read_bytes()
    password = os.environ.get(password_env, "")
    private_key = serialization.load_pem_private_key(
        key_data,
        password=password.encode("utf-8") if b"ENCRYPTED PRIVATE KEY" in key_data and password else None,
    )
    if not isinstance(private_key, ec.EllipticCurvePrivateKey):
        raise ValueError("sticker manifest key must be an EC private key")
    signature = private_key.sign(canonical_payload(manifest).encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    return manifest | {"manifestSignature": base64.b64encode(signature).decode("ascii")}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--descriptor", required=True, type=Path)
    parser.add_argument("--asset-root", required=True, type=Path)
    parser.add_argument("--private-key", required=True, type=Path)
    parser.add_argument("--private-key-password-env", default="MESHGRAM_UPDATE_PRIVATE_KEY_PASSWORD")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    root = args.asset_root.resolve(strict=True)
    descriptor = json.loads(args.descriptor.read_text(encoding="utf-8"))
    manifest = build_manifest(descriptor, root)
    signed = sign_manifest(manifest, args.private_key, args.private_key_password_env)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(signed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"packId": manifest["packId"], "version": manifest["version"],
                      "stickers": len(manifest["stickers"])}, indent=2))


if __name__ == "__main__":
    main()
