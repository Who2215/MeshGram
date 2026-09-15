#!/usr/bin/env python3
"""Build and sign a MeshGram sticker-pack manifest from local reviewed files."""

import argparse
import base64
import hashlib
import json
import os
import re
import struct
from pathlib import Path
from urllib.parse import urlparse


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
KINDS = {"LOTTIE", "PNG"}
PACK_ID = re.compile(r"[a-z][a-z0-9._-]{2,47}\Z")
ITEM_ID = re.compile(r"[a-z][a-z0-9._-]{2,47}/[a-z0-9][a-z0-9_-]{0,47}\Z")
CATEGORY = re.compile(r"[a-z][a-z0-9_-]{0,31}\Z")
MAX_STICKERS = 100
MAX_PACK_BYTES = 24 * 1024 * 1024
MAX_LOTTIE_BYTES = 512 * 1024
MAX_IMAGE_BYTES = 2 * 1024 * 1024
MAX_PREVIEW_BYTES = 256 * 1024


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


def safe_text(value: object, maximum: int, field: str) -> str:
    if not isinstance(value, str) or not value or len(value) > maximum or any(ord(char) < 32 for char in value):
        raise ValueError(f"{field} is empty, too long, or contains control characters")
    return value


def png_header(path: Path, maximum_dimension: int) -> tuple[int, int]:
    header = path.read_bytes()[:26]
    if len(header) < 26 or header[:8] != PNG_SIGNATURE or header[12:16] != b"IHDR":
        raise ValueError(f"invalid PNG header: {path.name}")
    chunk_length, width, height = struct.unpack(">III", header[8:12] + header[16:24])
    color_type = header[25]
    if chunk_length != 13 or not (1 <= width <= maximum_dimension) or not (1 <= height <= maximum_dimension):
        raise ValueError(f"unsafe PNG dimensions: {path.name}")
    if color_type not in (4, 6):
        raise ValueError(f"PNG must have an alpha channel: {path.name}")
    return width, height


def json_complexity(value: object, depth: int = 0, state: list[int] | None = None) -> tuple[int, int]:
    if state is None:
        state = [0, 0]
    state[0] += 1
    state[1] = max(state[1], depth)
    if state[0] > 50_000 or depth > 32:
        raise ValueError("Lottie JSON is too complex")
    children = value.values() if isinstance(value, dict) else value if isinstance(value, list) else ()
    for child in children:
        json_complexity(child, depth + 1, state)
    return state[0], state[1]


def build_manifest(descriptor: dict, asset_root: Path) -> dict:
    pack_id = safe_text(descriptor["packId"], 48, "packId")
    if not PACK_ID.fullmatch(pack_id):
        raise ValueError("invalid packId")
    sources = descriptor.get("stickers")
    if not isinstance(sources, list) or not (1 <= len(sources) <= MAX_STICKERS):
        raise ValueError("sticker count is outside the supported range")
    manifest = {
        "schemaVersion": 1,
        "packId": pack_id,
        "version": int(descriptor["version"]),
        "title": safe_text(descriptor["title"], 64, "title"),
        "attribution": safe_text(descriptor["attribution"], 160, "attribution"),
        "licenseUrl": descriptor["licenseUrl"],
        "stickers": [],
    }
    if manifest["version"] <= 0:
        raise ValueError("version must be positive")
    require_https(manifest["licenseUrl"], "licenseUrl")
    ids: set[str] = set()
    total_bytes = 0
    for source in sources:
        if source["kind"] not in KINDS:
            raise ValueError(f"unsupported kind: {source['kind']}")
        item_id = safe_text(source["id"], 96, "id")
        if not ITEM_ID.fullmatch(item_id) or not item_id.startswith(f"{pack_id}/") or item_id in ids:
            raise ValueError(f"invalid or duplicate sticker id: {item_id}")
        ids.add(item_id)
        safe_text(source["label"], 64, "label")
        if not CATEGORY.fullmatch(source["category"]):
            raise ValueError(f"invalid category: {source['category']}")
        require_https(source["assetUrl"], "assetUrl")
        require_https(source["previewUrl"], "previewUrl")
        asset = safe_file(asset_root, source["assetFile"])
        preview = safe_file(asset_root, source["previewFile"])
        if preview.stat().st_size > MAX_PREVIEW_BYTES:
            raise ValueError(f"preview is too large: {source['previewFile']}")
        png_header(preview, 512)
        width, height = int(source["width"]), int(source["height"])
        duration_ms, frame_rate = int(source.get("durationMs", 0)), int(source.get("frameRate", 0))
        if not (1 <= width <= 1024 and 1 <= height <= 1024):
            raise ValueError(f"unsafe sticker dimensions: {item_id}")
        maximum_asset_bytes = MAX_LOTTIE_BYTES if source["kind"] == "LOTTIE" else MAX_IMAGE_BYTES
        if not (1 <= asset.stat().st_size <= maximum_asset_bytes):
            raise ValueError(f"asset is too large: {source['assetFile']}")
        if source["kind"] == "LOTTIE":
            animation = json.loads(asset.read_text(encoding="utf-8"))
            for field in ("w", "h", "fr", "ip", "op"):
                if field not in animation:
                    raise ValueError(f"Lottie file is missing {field}: {source['assetFile']}")
            if any(item.get("p") for item in animation.get("assets", []) if isinstance(item, dict)):
                raise ValueError(f"external Lottie assets are forbidden: {source['assetFile']}")
            json_complexity(animation)
            actual_duration = round((animation["op"] - animation["ip"]) / animation["fr"] * 1000)
            if (animation["w"] != width or animation["h"] != height or
                    not (1 <= animation["fr"] <= 60) or animation["fr"] != frame_rate or
                    not (100 <= actual_duration <= 5_000) or abs(actual_duration - duration_ms) > 50):
                raise ValueError(f"Lottie metadata mismatch: {source['assetFile']}")
        else:
            actual_width, actual_height = png_header(asset, 1024)
            if (actual_width, actual_height) != (width, height) or duration_ms != 0 or frame_rate != 0:
                raise ValueError(f"PNG metadata mismatch: {source['assetFile']}")
        total_bytes += asset.stat().st_size + preview.stat().st_size
        if total_bytes > MAX_PACK_BYTES:
            raise ValueError("pack exceeds the total byte limit")
        manifest["stickers"].append(
            {
                key: source[key]
                for key in (
                    "id", "label", "category", "kind", "assetUrl", "previewUrl",
                    "width", "height"
                )
            }
            | {
                "durationMs": duration_ms,
                "frameRate": frame_rate,
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
