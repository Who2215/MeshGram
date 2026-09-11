#!/usr/bin/env python3
"""Publish idempotent MeshGram audience milestones to Telegram."""

from __future__ import annotations

import argparse
import json
import ssl
import sys
import time
import urllib.request
from pathlib import Path
from typing import Any

from telegram_release_publisher import DEFAULT_CHAT_ID, DEFAULT_TOKEN_FILE, PublisherError, api_request, load_token


DEFAULT_STATS_URL = "https://77-233-213-107.sslip.io/api/site-stats"
DEFAULT_STATE_FILE = Path(".github/site-stats-milestones.json")
DEFAULT_MILESTONES = (50, 100, 250, 500, 1_000, 2_500, 5_000, 10_000, 25_000, 50_000, 100_000)


def fetch_stats(url: str) -> dict[str, int]:
    if not url.startswith("https://"):
        raise PublisherError("Stats URL must use HTTPS")
    request = urllib.request.Request(url, headers={"User-Agent": "MeshGram-StatsPublisher/1"})
    try:
        with urllib.request.urlopen(request, timeout=20, context=ssl.create_default_context()) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except Exception as exc:
        raise PublisherError("Cannot fetch site stats") from exc
    if not isinstance(payload, dict) or payload.get("ok") is not True:
        raise PublisherError("Stats response is invalid")
    result: dict[str, int] = {}
    for field in ("visits", "downloads"):
        value = payload.get(field)
        if not isinstance(value, int) or value < 0:
            raise PublisherError(f"Stats field {field} is invalid")
        result[field] = value
    return result


def load_state(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {"sentMilestones": []}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PublisherError(f"Cannot read milestone state: {path}") from exc
    return value if isinstance(value, dict) else {"sentMilestones": []}


def save_state(path: Path, state: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def format_post(milestone: int, stats: dict[str, int]) -> str:
    return "\n".join([
        f"🎉 MeshGram растёт: уже {milestone} посещений сайта!",
        "",
        f"📲 Скачиваний APK: {stats['downloads']}",
        "🛰️ Спасибо каждому, кто тестирует связь и помогает проекту стать лучше.",
        "",
        "🔗 https://who2215.github.io/MeshGram/",
        "#MeshGram #сообщество #BLE #Android",
    ])


def run(args: argparse.Namespace) -> int:
    stats = fetch_stats(args.stats_url)
    state = load_state(args.state_file)
    sent = {int(value) for value in state.get("sentMilestones", []) if str(value).isdigit()}
    reached = [value for value in args.milestone if stats["visits"] >= value and value not in sent]
    if not reached:
        print(f"No new milestone: visits={stats['visits']} downloads={stats['downloads']}")
        return 0
    milestone = max(reached)
    post = format_post(milestone, stats)
    if args.dry_run:
        print(post)
        print(f"Would publish milestone {milestone} to {args.chat_id}")
        return 0
    token = load_token(args.token_file)
    api_request(token, "sendMessage", {"chat_id": args.chat_id, "text": post, "disable_web_page_preview": "true"})
    sent.update(reached)
    save_state(args.state_file, {
        "sentMilestones": sorted(sent),
        "lastStats": stats,
        "updatedAt": int(time.time()),
    })
    print(f"Published milestone {milestone}; marked {len(reached)} reached milestone(s)")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stats-url", default=DEFAULT_STATS_URL)
    parser.add_argument("--chat-id", default=DEFAULT_CHAT_ID)
    parser.add_argument("--token-file", type=Path, default=DEFAULT_TOKEN_FILE)
    parser.add_argument("--state-file", type=Path, default=DEFAULT_STATE_FILE)
    parser.add_argument("--milestone", type=int, action="append", default=list(DEFAULT_MILESTONES))
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        raise SystemExit(run(parse_args()))
    except PublisherError as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(2)

