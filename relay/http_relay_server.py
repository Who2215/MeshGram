#!/usr/bin/env python3
"""Small HTTPS-friendly relay for networks that break long-lived WebSockets.

The Cloudflare tunnel terminates HTTPS outside this process. This service only
handles authenticated JSON requests on localhost and stores opaque encrypted
frames in memory until the recipient polls.
"""

import argparse
import base64
import binascii
import json
import logging
import os
import secrets
import threading
import time
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Deque, Dict, Optional
from urllib.parse import parse_qs, urlparse

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


FRAME_TYPE = "MESH_RELAY_FRAME_V1"
AUTH_HELLO_TYPE = "MESH_RELAY_AUTH_HELLO_V1"
AUTH_CHALLENGE_TYPE = "MESH_RELAY_AUTH_CHALLENGE_V1"
AUTH_RESPONSE_TYPE = "MESH_RELAY_AUTH_RESPONSE_V1"
AUTH_ACCEPTED_TYPE = "MESH_RELAY_AUTH_ACCEPTED_V1"
MAX_BODY_BYTES = 2 * 1024 * 1024
MAX_PAYLOAD_BYTES = 256 * 1024
MAX_NODE_ID_LENGTH = 96
MAX_PUBLIC_KEY_LENGTH = 2048
MAX_SIGNATURE_LENGTH = 256
# Mobile VPNs and captive/proxy paths can delay the second request. Keep the
# challenge short-lived, but long enough to avoid false expiry on a slow path.
CHALLENGE_TTL_SECONDS = 180
SESSION_TTL_SECONDS = 24 * 60 * 60
# A timed grace period lets in-flight VPN/proxy requests finish after a
# reconnect replaces the node's active session.
RETIRED_SESSION_GRACE_SECONDS = 90
QUEUE_TTL_SECONDS = 30 * 24 * 60 * 60
MAX_QUEUE_PER_NODE = 128
# Android retries from its encrypted outbox every few seconds. Keep a short
# duplicate window, then accept the retry if the previous poll was lost.
FRAME_DEDUP_WINDOW_SECONDS = 5


class HttpRelayHub:
    def __init__(self, admission_token: str = "") -> None:
        self.admission_token = admission_token
        self.lock = threading.RLock()
        self.challenges: Dict[str, tuple[str, str, float, str, str]] = {}
        self.sessions: Dict[str, tuple[str, float]] = {}
        self.retired_sessions: Dict[str, tuple[str, float]] = {}
        self.node_sessions: Dict[str, str] = {}
        self.pending: Dict[str, Deque[tuple[float, str]]] = defaultdict(deque)
        # Keep a small per-recipient index so retries do not fill the queue
        # while the first copy is still waiting to be polled.
        self.pending_frame_ids: Dict[str, set[str]] = defaultdict(set)
        self.seen_frames: Dict[str, float] = {}

    @staticmethod
    def _bounded(value: object, limit: int, allow_blank: bool = False) -> bool:
        return isinstance(value, str) and (allow_blank or bool(value)) and len(value) <= limit

    def _cleanup_locked(self, now: float) -> None:
        self.challenges = {
            key: value for key, value in self.challenges.items() if value[2] > now
        }
        expired_sessions = [
            session_id for session_id, (_, expires_at) in self.sessions.items()
            if expires_at <= now
        ]
        for session_id in expired_sessions:
            node_id = self.sessions.pop(session_id)[0]
            if self.node_sessions.get(node_id) == session_id:
                self.node_sessions.pop(node_id, None)
        self.retired_sessions = {
            session_id: value
            for session_id, value in self.retired_sessions.items()
            if value[1] > now
        }
        self.seen_frames = {
            key: seen_at for key, seen_at in self.seen_frames.items()
            if now - seen_at < QUEUE_TTL_SECONDS
        }

    def _public_key(self, node_id: object, public_key_b64: object):
        if not self._bounded(node_id, MAX_NODE_ID_LENGTH):
            raise ValueError("invalid node id")
        if not self._bounded(public_key_b64, MAX_PUBLIC_KEY_LENGTH):
            raise ValueError("invalid public key")
        try:
            key = serialization.load_der_public_key(
                base64.b64decode(public_key_b64, validate=True)
            )
        except (ValueError, TypeError, binascii.Error) as exc:
            raise ValueError("invalid public key") from exc
        if not isinstance(key, ec.EllipticCurvePublicKey):
            raise ValueError("invalid public key type")
        return key

    def hello(self, data: dict) -> dict:
        node_id = data.get("nodeId")
        public_key_b64 = data.get("signingPublicKey")
        self._public_key(node_id, public_key_b64)
        if self.admission_token and data.get("admissionToken") != self.admission_token:
            raise ValueError("admission denied")
        now = time.time()
        session_id = secrets.token_urlsafe(18)
        challenge = base64.b64encode(os.urandom(32)).decode("ascii")
        with self.lock:
            self._cleanup_locked(now)
            self.challenges[session_id] = (
                session_id,
                challenge,
                now + CHALLENGE_TTL_SECONDS,
                node_id,
                public_key_b64,
            )
        return {
            "type": AUTH_CHALLENGE_TYPE,
            "sessionId": session_id,
            "challengeBase64": challenge,
            "expiresAtMs": int((now + CHALLENGE_TTL_SECONDS) * 1000),
        }

    def authenticate(self, data: dict) -> dict:
        session_id = data.get("sessionId")
        node_id = data.get("nodeId")
        public_key_b64 = data.get("signingPublicKey")
        signature_b64 = data.get("signatureBase64")
        if not self._bounded(session_id, 96):
            raise ValueError("invalid session")
        if not self._bounded(signature_b64, MAX_SIGNATURE_LENGTH):
            raise ValueError("invalid signature")
        with self.lock:
            challenge = self.challenges.get(session_id)
        if challenge is None:
            raise ValueError("unknown challenge")
        expected_session, challenge_b64, expires_at, expected_node, expected_key = challenge
        if time.time() > expires_at:
            raise ValueError("expired challenge")
        if node_id != expected_node or public_key_b64 != expected_key:
            raise ValueError("identity mismatch")
        self._public_key(node_id, public_key_b64)
        signing_payload = "|".join([
            "MESH_RELAY_AUTH_V1",
            expected_session,
            challenge_b64,
            node_id,
            public_key_b64,
        ]).encode("utf-8")
        try:
            public_key = serialization.load_der_public_key(
                base64.b64decode(public_key_b64, validate=True)
            )
            signature = base64.b64decode(signature_b64, validate=True)
            public_key.verify(signature, signing_payload, ec.ECDSA(hashes.SHA256()))
        except (ValueError, TypeError, binascii.Error, InvalidSignature) as exc:
            raise ValueError("signature rejected") from exc
        now = time.time()
        with self.lock:
            self._cleanup_locked(now)
            old_session = self.node_sessions.get(node_id)
            if old_session:
                self.sessions.pop(old_session, None)
                self.retired_sessions[old_session] = (
                    node_id,
                    now + RETIRED_SESSION_GRACE_SECONDS,
                )
            self.challenges.pop(session_id, None)
            self.sessions[session_id] = (node_id, now + SESSION_TTL_SECONDS)
            self.node_sessions[node_id] = session_id
        logging.info("HTTP client authenticated node=%s", node_id)
        return {
            "type": AUTH_ACCEPTED_TYPE,
            "nodeId": node_id,
            "expiresAtMs": int((now + SESSION_TTL_SECONDS) * 1000),
        }

    def _session_node(self, session_id: object) -> str:
        if not self._bounded(session_id, 96):
            raise ValueError("invalid session")
        now = time.time()
        with self.lock:
            self._cleanup_locked(now)
            session = self.sessions.get(session_id)
            if session is not None:
                node_id, _ = session
                self.sessions[session_id] = (node_id, now + SESSION_TTL_SECONDS)
                return node_id
            retired = self.retired_sessions.get(session_id)
            if retired is None or retired[1] <= now:
                raise ValueError("session expired")
            node_id, _ = retired
            return node_id

    @staticmethod
    def _validate_frame(frame: object) -> dict:
        if not isinstance(frame, dict) or frame.get("type") != FRAME_TYPE:
            raise ValueError("invalid frame")
        for key in ("frameId", "viaNodeId", "payloadBase64"):
            if not isinstance(frame.get(key), str) or not frame[key]:
                raise ValueError("invalid frame field")
        if not HttpRelayHub._bounded(frame["frameId"], 96):
            raise ValueError("invalid frame id")
        if not HttpRelayHub._bounded(frame["viaNodeId"], MAX_NODE_ID_LENGTH):
            raise ValueError("invalid sender")
        if not HttpRelayHub._bounded(
            frame.get("recipientNodeId", ""), MAX_NODE_ID_LENGTH, allow_blank=True
        ):
            raise ValueError("invalid recipient")
        try:
            payload = base64.b64decode(frame["payloadBase64"], validate=True)
        except (ValueError, binascii.Error) as exc:
            raise ValueError("invalid payload") from exc
        if not 0 < len(payload) <= MAX_PAYLOAD_BYTES:
            raise ValueError("payload too large")
        return frame

    def publish(self, session_id: object, frame: object) -> None:
        node_id = self._session_node(session_id)
        validated = self._validate_frame(frame)
        if validated["viaNodeId"] != node_id:
            raise ValueError("sender mismatch")
        now = time.time()
        raw = json.dumps(validated, separators=(",", ":"))
        with self.lock:
            last_seen_at = self.seen_frames.get(validated["frameId"])
            if last_seen_at is not None and now - last_seen_at < FRAME_DEDUP_WINDOW_SECONDS:
                return
            self.seen_frames[validated["frameId"]] = now
            recipient = validated.get("recipientNodeId", "")
            if recipient:
                targets = [recipient]
            else:
                targets = [
                    node for node in self.node_sessions
                    if node != node_id
                ]
            for target in targets:
                queue = self.pending[target]
                if validated["frameId"] in self.pending_frame_ids[target]:
                    continue
                queue.append((now + QUEUE_TTL_SECONDS, raw))
                self.pending_frame_ids[target].add(validated["frameId"])
                while len(queue) > MAX_QUEUE_PER_NODE:
                    _, dropped_raw = queue.popleft()
                    try:
                        dropped_frame_id = json.loads(dropped_raw).get("frameId")
                    except json.JSONDecodeError:
                        dropped_frame_id = None
                    if dropped_frame_id:
                        self.pending_frame_ids[target].discard(dropped_frame_id)
        logging.info("HTTP frame queued sender=%s recipient=%s", node_id, validated.get("recipientNodeId", ""))

    def poll(self, session_id: object, limit: int = 8) -> list[dict]:
        node_id = self._session_node(session_id)
        limit = max(1, min(int(limit), 32))
        now = time.time()
        result: list[dict] = []
        with self.lock:
            queue = self.pending[node_id]
            while queue and len(result) < limit:
                expires_at, raw = queue.popleft()
                try:
                    frame = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                frame_id = frame.get("frameId")
                if frame_id:
                    self.pending_frame_ids[node_id].discard(frame_id)
                if expires_at <= now:
                    continue
                result.append(frame)
        logging.info("HTTP poll node=%s frames=%d", node_id, len(result))
        return result


class RelayRequestHandler(BaseHTTPRequestHandler):
    hub: HttpRelayHub
    server_version = "MeshGramHttpRelay/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args) -> None:
        logging.info("HTTP %s - %s", self.address_string(), fmt % args)

    def _json_body(self) -> dict:
        raw_length = self.headers.get("Content-Length", "0")
        try:
            length = int(raw_length)
        except ValueError as exc:
            raise ValueError("invalid content length") from exc
        if length < 0 or length > MAX_BODY_BYTES:
            raise ValueError("body too large")
        raw = self.rfile.read(length)
        data = json.loads(raw.decode("utf-8"))
        if not isinstance(data, dict):
            raise ValueError("JSON object required")
        return data

    def _send_json(self, status: int, data: dict) -> None:
        raw = json.dumps(data, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(raw)

    def _send_error(self, status: int, message: str) -> None:
        self._send_json(status, {"ok": False, "error": message})

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/healthz":
            self._send_json(200, {"ok": True, "service": "meshgram-http-relay"})
            return
        if parsed.path != "/api/poll":
            self._send_error(404, "not found")
            return
        query = parse_qs(parsed.query)
        session_id = query.get("sessionId", [""])[0]
        try:
            limit = int(query.get("max", ["8"])[0])
            frames = self.hub.poll(session_id, limit)
            self._send_json(200, {"ok": True, "frames": frames})
        except (ValueError, TypeError) as exc:
            self._send_error(401, str(exc))

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        try:
            data = self._json_body()
            if parsed.path == "/api/hello":
                self._send_json(200, self.hub.hello(data))
            elif parsed.path == "/api/auth":
                self._send_json(200, self.hub.authenticate(data))
            elif parsed.path == "/api/frame":
                self.hub.publish(data.get("sessionId"), data.get("frame"))
                self._send_json(200, {"ok": True})
            else:
                self._send_error(404, "not found")
        except (ValueError, TypeError, json.JSONDecodeError) as exc:
            logging.warning("HTTP relay rejected path=%s: %s", parsed.path, exc)
            self._send_error(400, str(exc))
        except Exception:
            logging.exception("HTTP relay request failed")
            self._send_error(500, "internal relay error")


def main() -> None:
    parser = argparse.ArgumentParser(description="MeshGram HTTPS fallback relay")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8788)
    parser.add_argument("--admission-token", default=os.environ.get("MESHGRAM_RELAY_ADMISSION_TOKEN", ""))
    args = parser.parse_args()
    hub = HttpRelayHub(admission_token=args.admission_token)
    RelayRequestHandler.hub = hub
    server = ThreadingHTTPServer((args.host, args.port), RelayRequestHandler)
    logging.info("HTTP relay listening on http://%s:%d", args.host, args.port)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    main()
