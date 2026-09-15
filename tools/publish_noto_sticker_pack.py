#!/usr/bin/env python3
"""Download, audit, and sign a selected official Noto Animated Emoji pack."""

import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path
from urllib.request import Request, urlopen

from sign_sticker_pack import build_manifest, json_complexity, sign_manifest


API_URL = "https://googlefonts.github.io/noto-emoji-animation/data/api.json"
ANIMATION_URL = "https://fonts.gstatic.com/s/e/notoemoji/latest/{codepoint}/lottie.json"
PREVIEW_URL = "https://raw.githubusercontent.com/googlefonts/noto-emoji/main/png/128/emoji_u{codepoint}.png"
USER_AGENT = "MeshGram sticker pack publisher/1.0"


def download(url: str) -> bytes:
    request = Request(url, headers={"User-Agent": USER_AGENT})
    with urlopen(request, timeout=30) as response:
        if response.status != 200:
            raise ValueError(f"download failed ({response.status}): {url}")
        return response.read()


def preview_codepoint(codepoint: str) -> str:
    return "_".join(part for part in codepoint.split("_") if part != "fe0f")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def build_pack(config_path: Path, private_key: Path, output_root: Path, password_env: str) -> dict:
    config = json.loads(config_path.read_text(encoding="utf-8"))
    api = json.loads(download(API_URL).decode("utf-8"))
    available = {icon["codepoint"]: icon for icon in api["icons"]}
    pack_id = config["packId"]
    version = int(config["version"])
    target = output_root / pack_id / str(version)
    if target.exists():
        raise ValueError(f"refusing to overwrite an existing pack: {target}")

    repo_root = Path(__file__).resolve().parents[1]
    license_template = repo_root / "site" / "stickers" / "noto.reactions" / "1"
    descriptor = {
        "packId": pack_id,
        "version": version,
        "title": config["title"],
        "attribution": config["attribution"],
        "licenseUrl": config["licenseUrl"],
        "stickers": [],
    }
    source_records = []
    with tempfile.TemporaryDirectory(prefix="meshgram-noto-") as temporary:
        stage = Path(temporary)
        for selected in config["stickers"]:
            codepoint = selected["codepoint"]
            if codepoint not in available:
                raise ValueError(f"codepoint is not in the official animated catalog: {codepoint}")
            item_id = selected["id"]
            animation_source = ANIMATION_URL.format(codepoint=codepoint)
            preview_source = PREVIEW_URL.format(codepoint=preview_codepoint(codepoint))
            animation_data = download(animation_source)
            preview_data = download(preview_source)
            animation = json.loads(animation_data.decode("utf-8"))
            try:
                nodes, depth = json_complexity(animation)
            except ValueError as error:
                raise ValueError(f"unsafe animation {item_id} ({codepoint}): {error}") from error
            duration_ms = round((animation["op"] - animation["ip"]) / animation["fr"] * 1000)
            animation_name = f"{item_id}.json"
            preview_name = f"{item_id}.preview.png"
            (stage / animation_name).write_bytes(animation_data)
            (stage / preview_name).write_bytes(preview_data)
            public_base = config["publicBaseUrl"].rstrip("/")
            descriptor["stickers"].append({
                "id": f"{pack_id}/{item_id}",
                "label": selected["label"],
                "category": config["category"],
                "kind": "LOTTIE",
                "assetFile": animation_name,
                "previewFile": preview_name,
                "assetUrl": f"{public_base}/{animation_name}",
                "previewUrl": f"{public_base}/{preview_name}",
                "width": int(animation["w"]),
                "height": int(animation["h"]),
                "durationMs": duration_ms,
                "frameRate": int(animation["fr"]),
            })
            source_records.append({
                "id": f"{pack_id}/{item_id}",
                "codepoint": codepoint,
                "animationSource": animation_source,
                "previewSource": preview_source,
                "author": "Google Fonts",
                "animationLicense": "CC-BY-4.0",
                "previewLicense": "Apache-2.0",
                "jsonNodes": nodes,
                "jsonDepth": depth,
                "animationSha256": sha256(animation_data),
                "previewSha256": sha256(preview_data),
            })

        manifest = build_manifest(descriptor, stage)
        signed = sign_manifest(manifest, private_key, password_env)
        target.mkdir(parents=True)
        for file in stage.iterdir():
            shutil.copy2(file, target / file.name)
        (target / "manifest.json").write_text(
            json.dumps(signed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        (target / "sources.json").write_text(
            json.dumps(source_records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        for name in ("LICENSE-CC-BY-4.0.txt", "LICENSE-APACHE-2.0.txt"):
            shutil.copy2(license_template / name, target / name)
        (target / "NOTICE.md").write_text(
            f"# {config['title']} attribution\n\n"
            "Animations: Google Fonts Noto Animated Emoji, CC BY 4.0.\n\n"
            "Static previews: Google Noto Emoji, Apache 2.0.\n\n"
            f"Exact source URLs and SHA-256 checksums are recorded in `sources.json`.\n",
            encoding="utf-8",
        )
    return {"packId": pack_id, "version": version, "stickers": len(source_records), "target": str(target)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--private-key", required=True, type=Path)
    parser.add_argument("--output-root", default=Path("site/stickers"), type=Path)
    parser.add_argument("--private-key-password-env", default="MESHGRAM_UPDATE_PRIVATE_KEY_PASSWORD")
    args = parser.parse_args()
    result = build_pack(
        args.config.resolve(strict=True),
        args.private_key.resolve(strict=True),
        args.output_root.resolve(),
        args.private_key_password_env,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
