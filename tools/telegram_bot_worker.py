#!/usr/bin/env python3
"""Process MeshGram bot commands from a scheduled GitHub Actions run.

GitHub Actions is not a permanent server. The workflow calls this worker every
five minutes, performs a short long-poll, handles private-chat commands, and
commits only the numeric update offset back to the repository.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

from telegram_release_publisher import (
    PublisherError,
    api_request,
    fetch_manifest,
    load_token,
)


DEFAULT_MANIFEST_URL = "https://who2215.github.io/MeshGram/release.json"
DEFAULT_CHAT_ID = "@MeshGram"
DEFAULT_SITE_URL = "https://who2215.github.io/MeshGram/"
DEFAULT_DONATION_URL = "https://yookassa.ru/my/i/apXuo0ctyNYY/l"
DEFAULT_OFFSET_FILE = Path(".github/telegram-bot-offset.json")
POLL_SECONDS = 20

COMMANDS = [
    {"command": "start", "description": "О MeshGram / About MeshGram"},
    {"command": "help", "description": "Список команд / Command list"},
    {"command": "latest", "description": "Последний релиз / Latest release"},
    {"command": "download", "description": "Скачать APK / Download APK"},
    {"command": "status", "description": "Статус проекта / Project status"},
    {"command": "support", "description": "Поддержать проект / Support"},
    {"command": "report", "description": "Сообщить о проблеме / Report a bug"},
]


def load_offset(path: Path) -> int:
    if not path.exists():
        return 0
    try:
        value = json.loads(path.read_text(encoding="utf-8")).get("offset", 0)
    except (OSError, json.JSONDecodeError) as exc:
        raise PublisherError(f"Cannot read bot offset: {path}") from exc
    return value if isinstance(value, int) and value >= 0 else 0


def save_offset(path: Path, offset: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix="meshgram-bot-", suffix=".json", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump({"offset": offset, "updatedAt": int(time.time())}, stream, indent=2)
            stream.write("\n")
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def language_for(message: dict[str, Any]) -> str:
    code = str(message.get("from", {}).get("language_code", "ru")).lower()
    return "en" if code.startswith("en") else "ru"


def command_for(text: str) -> str:
    command = text.strip().split(maxsplit=1)[0].lower()
    return command.split("@", maxsplit=1)[0]


def help_text(language: str) -> str:
    if language == "en":
        return (
            "MeshGram bot commands:\n"
            "/latest - latest Android release\n"
            "/download - download APK\n"
            "/status - project links and release status\n"
            "/support - support development\n"
            "/report - send a bug report\n"
            "/help - this list"
        )
    return (
        "Команды MeshGram:\n"
        "/latest - последний релиз Android\n"
        "/download - скачать APK\n"
        "/status - ссылки и статус релиза\n"
        "/support - поддержать разработку\n"
        "/report - сообщить о проблеме\n"
        "/help - этот список"
    )


def start_text(language: str) -> str:
    if language == "en":
        return (
            "MeshGram is a BLE-first messenger with an optional Internet relay.\n\n"
            "Use /latest to get the newest Android build or /help for commands."
        )
    return (
        "MeshGram - мессенджер с приоритетом BLE и резервным интернет-релеем.\n\n"
        "Используйте /latest для последней версии Android или /help для списка команд."
    )


def release_text(manifest: dict[str, Any], language: str, download_only: bool = False) -> str:
    version = manifest["versionName"]
    apk_url = manifest["apkUrl"]
    if language == "en":
        if download_only:
            return f"MeshGram {version}\nDownload APK: {apk_url}"
        return f"Latest MeshGram release: {version}\nDownload APK: {apk_url}"
    if download_only:
        return f"MeshGram {version}\nСкачать APK: {apk_url}"
    return f"Последний релиз MeshGram: {version}\nСкачать APK: {apk_url}"


def response_for(command: str, language: str, manifest_url: str) -> str:
    if command in {"/start", "/help"}:
        return start_text(language) if command == "/start" else help_text(language)
    if command in {"/latest", "/download"}:
        manifest = fetch_manifest(manifest_url)
        return release_text(manifest, language, download_only=command == "/download")
    if command == "/status":
        if language == "en":
            return f"Project site: {DEFAULT_SITE_URL}\nChannel: https://t.me/MeshGram\nLatest manifest: {manifest_url}"
        return f"Сайт проекта: {DEFAULT_SITE_URL}\nКанал: https://t.me/MeshGram\nМанифест релиза: {manifest_url}"
    if command == "/support":
        if language == "en":
            return f"Support MeshGram development:\n{DEFAULT_DONATION_URL}"
        return f"Поддержать разработку MeshGram:\n{DEFAULT_DONATION_URL}"
    if command == "/report":
        if language == "en":
            return "Create a bug report here:\nhttps://github.com/Who2215/MeshGram/issues/new/choose"
        return "Создать сообщение об ошибке можно здесь:\nhttps://github.com/Who2215/MeshGram/issues/new/choose"
    return help_text(language)


def set_commands(token: str) -> None:
    api_request(token, "setMyCommands", {"commands": json.dumps(COMMANDS, ensure_ascii=False)})


def send_reply(token: str, chat_id: int, text: str) -> None:
    api_request(
        token,
        "sendMessage",
        {"chat_id": chat_id, "text": text, "disable_web_page_preview": "true"},
    )


def process_update(token: str, update: dict[str, Any], manifest_url: str) -> None:
    message = update.get("message")
    if not isinstance(message, dict) or message.get("chat", {}).get("type") != "private":
        return
    text = message.get("text")
    if not isinstance(text, str) or not text.startswith("/"):
        return
    chat_id = message["chat"].get("id")
    if not isinstance(chat_id, int):
        return
    language = language_for(message)
    try:
        reply = response_for(command_for(text), language, manifest_url)
    except PublisherError:
        reply = (
            "Не удалось получить данные релиза, попробуйте позже."
            if language == "ru"
            else "The release data is temporarily unavailable; try again later."
        )
    send_reply(token, chat_id, reply)


def poll_once(token: str, offset_path: Path, manifest_url: str) -> int:
    offset = load_offset(offset_path)
    payload = api_request(
        token,
        "getUpdates",
        {
            "offset": offset,
            "timeout": POLL_SECONDS,
            "allowed_updates": json.dumps(["message"]),
        },
    )
    updates = payload.get("result", [])
    if not isinstance(updates, list):
        raise PublisherError("Telegram returned an invalid updates list")
    processed = 0
    for update in updates:
        if not isinstance(update, dict):
            continue
        update_id = update.get("update_id")
        if not isinstance(update_id, int):
            continue
        process_update(token, update, manifest_url)
        offset = max(offset, update_id + 1)
        save_offset(offset_path, offset)
        processed += 1
    return processed


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--token-file", type=Path, default=Path(r"H:\mesh-workspace\secrets\telegram_bot_token.txt"))
    parser.add_argument("--offset-file", type=Path, default=DEFAULT_OFFSET_FILE)
    parser.add_argument("--manifest-url", default=DEFAULT_MANIFEST_URL)
    parser.add_argument("--register-commands", action="store_true")
    parser.add_argument("--once", action="store_true", help="Poll once and exit")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        token = load_token(args.token_file)
        if args.register_commands:
            set_commands(token)
            print("Telegram bot commands registered")
        while True:
            print(f"Processed bot updates: {poll_once(token, args.offset_file, args.manifest_url)}")
            if args.once:
                return 0
    except KeyboardInterrupt:
        return 0
    except PublisherError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
