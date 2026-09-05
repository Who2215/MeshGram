#!/usr/bin/env python3
"""Publish verified MeshGram releases to the project Telegram channel.

The bot token is read from a local file or environment variable and is never
stored in the repository. The publisher is idempotent: a version is recorded
only after Telegram confirms that the post was sent successfully.
"""

from __future__ import annotations

import argparse
import json
import os
import ssl
import sys
import tempfile
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


DEFAULT_MANIFEST_URL = "https://who2215.github.io/MeshGram/release.json"
DEFAULT_CHAT_ID = "@MeshGram"
DEFAULT_TOKEN_FILE = Path(r"H:\mesh-workspace\secrets\telegram_bot_token.txt")
DEFAULT_STATE_FILE = Path(r"H:\mesh-workspace\secrets\telegram_release_state.json")
DEFAULT_AVATAR = (
    Path(r"C:\Users\Admin\.codex\generated_images\019dbada-56d5-7a61-9781-e4861ad2cffd")
    / "exec-141604f0-4667-4f3a-8a99-c824825dc8c3.png"
)


class PublisherError(RuntimeError):
    """Raised for expected configuration, network, or API failures."""


def load_token(token_file: Path) -> str:
    token = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
    if not token:
        try:
            token = token_file.read_text(encoding="utf-8").strip()
        except OSError as exc:
            raise PublisherError(f"Cannot read token file: {token_file}") from exc
    if not token or any(char.isspace() for char in token):
        raise PublisherError("Telegram bot token is empty or malformed")
    return token


def api_request(token: str, method: str, fields: dict[str, Any] | None = None,
                file_field: tuple[str, Path] | None = None) -> dict[str, Any]:
    url = f"https://api.telegram.org/bot{token}/{method}"
    fields = fields or {}
    if file_field is None:
        body = urllib.parse.urlencode({key: str(value) for key, value in fields.items()}).encode()
        request = urllib.request.Request(
            url, data=body, headers={"Content-Type": "application/x-www-form-urlencoded"}
        )
    else:
        field_name, file_path = file_field
        if not file_path.is_file():
            raise PublisherError(f"File does not exist: {file_path}")
        boundary = f"----MeshGram{os.urandom(12).hex()}".encode()
        chunks: list[bytes] = []
        for key, value in fields.items():
            chunks.extend([
                b"--" + boundary + b"\r\n",
                f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode(),
                str(value).encode(),
                b"\r\n",
            ])
        chunks.extend([
            b"--" + boundary + b"\r\n",
            f'Content-Disposition: form-data; name="{field_name}"; filename="{file_path.name}"\r\n'.encode(),
            b"Content-Type: application/octet-stream\r\n\r\n",
            file_path.read_bytes(),
            b"\r\n--" + boundary + b"--\r\n",
        ])
        request = urllib.request.Request(
            url,
            data=b"".join(chunks),
            headers={"Content-Type": f"multipart/form-data; boundary={boundary.decode()}"},
        )

    try:
        with urllib.request.urlopen(request, timeout=30, context=ssl.create_default_context()) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except Exception as exc:
        raise PublisherError(f"Telegram API request failed: {method}") from exc
    if not payload.get("ok"):
        description = str(payload.get("description", "unknown Telegram API error"))
        raise PublisherError(f"Telegram API rejected {method}: {description}")
    return payload


def fetch_manifest(url: str) -> dict[str, Any]:
    if not url.startswith("https://"):
        raise PublisherError("Manifest URL must use HTTPS")
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "MeshGram-ReleasePublisher/1"})
        with urllib.request.urlopen(request, timeout=30, context=ssl.create_default_context()) as response:
            manifest = json.loads(response.read().decode("utf-8"))
    except Exception as exc:
        raise PublisherError("Cannot fetch release manifest") from exc
    if not isinstance(manifest, dict):
        raise PublisherError("Release manifest must be a JSON object")
    if manifest.get("packageName") != "com.meshchat.app":
        raise PublisherError("Release manifest package name is not MeshGram")
    version_code = manifest.get("versionCode")
    version_name = manifest.get("versionName")
    apk_url = manifest.get("apkUrl")
    changelog = manifest.get("changelog", manifest.get("notes", []))
    if not isinstance(version_code, int) or version_code < 1:
        raise PublisherError("Release manifest has an invalid versionCode")
    if not isinstance(version_name, str) or not version_name:
        raise PublisherError("Release manifest has an invalid versionName")
    if not isinstance(apk_url, str) or not apk_url.startswith("https://"):
        raise PublisherError("Release manifest APK URL must use HTTPS")
    if not isinstance(changelog, list) or not all(isinstance(item, str) for item in changelog):
        raise PublisherError("Release manifest changelog must be a list of strings")
    return manifest


def load_state(path: Path) -> dict[str, Any]:
    try:
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    except (OSError, json.JSONDecodeError) as exc:
        raise PublisherError(f"Cannot read publisher state: {path}") from exc


def save_state(path: Path, state: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix="meshgram-release-", suffix=".json", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(state, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        os.replace(temp_name, path)
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)


def acquire_lock(path: Path) -> Path | None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        try:
            if time.time() - path.stat().st_mtime > 3600:
                path.unlink()
        except OSError:
            return None
    try:
        descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        with os.fdopen(descriptor, "w", encoding="ascii", errors="strict") as stream:
            stream.write(str(os.getpid()))
    except FileExistsError:
        return None
    except OSError as exc:
        raise PublisherError(f"Cannot create publisher lock: {path}") from exc
    return path


def format_release_post(manifest: dict[str, Any], site_url: str, donation_url: str) -> str:
    lines = [
        f"MeshGram {manifest['versionName']} опубликован",
        "",
        "Что изменилось:",
    ]
    lines.extend(f"• {item}" for item in manifest.get("changelog", manifest.get("notes", [])))
    lines.extend([
        "",
        f"Скачать Android: {manifest['apkUrl']}",
        f"Сайт проекта: {site_url}",
        f"Поддержать разработку: {donation_url}",
    ])
    return "\n".join(lines)


def publish_once(args: argparse.Namespace) -> int:
    manifest = fetch_manifest(args.manifest_url)
    state = load_state(args.state_file)
    version_code = manifest["versionCode"]
    previous_code = state.get("versionCode", 0)
    if not isinstance(previous_code, int):
        previous_code = 0
    post = format_release_post(manifest, args.site_url, args.donation_url)
    if args.dry_run:
        print(post)
        print(f"Would publish version {manifest['versionName']} to {args.chat_id}")
        return 0
    if args.initialize:
        save_state(args.state_file, {
            "versionCode": version_code,
            "versionName": manifest["versionName"],
            "initializedAt": int(time.time()),
        })
        print(f"Initialized publisher at MeshGram {manifest['versionName']}")
        return 0
    if version_code <= previous_code:
        print(f"No new release: {manifest['versionName']}")
        return 0
    lock = acquire_lock(args.lock_file)
    if lock is None:
        print("Another publisher run is active")
        return 0
    try:
        token = load_token(args.token_file)
        # Re-read state after acquiring the lock to avoid duplicate posts.
        state = load_state(args.state_file)
        previous_code = state.get("versionCode", 0)
        if isinstance(previous_code, int) and version_code <= previous_code:
            print(f"No new release: {manifest['versionName']}")
            return 0
        response = api_request(
            token,
            "sendMessage",
            {"chat_id": args.chat_id, "text": post, "disable_web_page_preview": "true"},
        )
        message_id = response.get("result", {}).get("message_id")
        save_state(args.state_file, {
            "versionCode": version_code,
            "versionName": manifest["versionName"],
            "messageId": message_id,
            "publishedAt": int(time.time()),
        })
        print(f"Published MeshGram {manifest['versionName']} to {args.chat_id}")
        return 0
    finally:
        try:
            lock.unlink()
        except OSError:
            pass


def set_avatar(args: argparse.Namespace) -> int:
    token = load_token(args.token_file)
    if args.dry_run:
        print(f"Would set channel avatar from {args.avatar}")
        return 0
    api_request(token, "setChatPhoto", {"chat_id": args.chat_id}, ("photo", args.avatar))
    print(f"Channel avatar updated for {args.chat_id}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--token-file", type=Path, default=DEFAULT_TOKEN_FILE)
    common.add_argument("--chat-id", default=DEFAULT_CHAT_ID)
    common.add_argument("--dry-run", action="store_true")

    publish = subparsers.add_parser("publish", parents=[common])
    publish.add_argument("--manifest-url", default=DEFAULT_MANIFEST_URL)
    publish.add_argument("--state-file", type=Path, default=DEFAULT_STATE_FILE)
    publish.add_argument("--lock-file", type=Path, default=Path(r"H:\mesh-workspace\secrets\telegram_release_publisher.lock"))
    publish.add_argument("--site-url", default="https://who2215.github.io/MeshGram/")
    publish.add_argument("--donation-url", default="https://yookassa.ru/my/i/apXuo0ctyNYY/l")
    publish.add_argument(
        "--initialize",
        action="store_true",
        help="Record the current manifest without publishing it",
    )

    avatar = subparsers.add_parser("avatar", parents=[common])
    avatar.add_argument("--avatar", type=Path, default=DEFAULT_AVATAR)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        return publish_once(args) if args.command == "publish" else set_avatar(args)
    except PublisherError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
