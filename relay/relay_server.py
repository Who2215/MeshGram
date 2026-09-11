#!/usr/bin/env python3
import argparse
import asyncio
import base64
import binascii
import json
import logging
import os
import secrets
import time
from collections import deque
from dataclasses import dataclass
from typing import Deque, Dict, Optional, Set

import websockets
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from websockets.server import WebSocketServerProtocol


FRAME_TYPE = "MESH_RELAY_FRAME_V1"
FRAME_OVERHEAD_BYTES = 512
AUTH_HELLO_TYPE = "MESH_RELAY_AUTH_HELLO_V1"
AUTH_CHALLENGE_TYPE = "MESH_RELAY_AUTH_CHALLENGE_V1"
AUTH_RESPONSE_TYPE = "MESH_RELAY_AUTH_RESPONSE_V1"
AUTH_ACCEPTED_TYPE = "MESH_RELAY_AUTH_ACCEPTED_V1"
AUTH_REJECTED_TYPE = "MESH_RELAY_AUTH_REJECTED_V1"
RELAY_PING_TYPE = "MESH_RELAY_PING_V1"
RELAY_PONG_TYPE = "MESH_RELAY_PONG_V1"
AUTH_CHALLENGE_TTL_SECONDS = 30
# Keep opaque frames long enough for an offline recipient to reconnect.
# The per-recipient queue remains bounded below, so retention cannot grow
# without limit for a single node.
QUEUE_TTL_SECONDS = 30 * 24 * 60 * 60
MAX_NODE_ID_LENGTH = 96
MAX_PUBLIC_KEY_LENGTH = 2048
MAX_SIGNATURE_LENGTH = 256


@dataclass(frozen=True)
class Config:
    host: str
    port: int
    path: str
    max_message_size: int
    max_clients: int
    max_frames_per_minute: int
    max_payload_size: int
    admission_token: str = ""


class RelayHub:
    def __init__(self, config: Config) -> None:
        self.config = config
        self.clients: Set[WebSocketServerProtocol] = set()
        self.lock = asyncio.Lock()
        self.rate_windows: Dict[WebSocketServerProtocol, Deque[float]] = {}
        self.auth_nodes: Dict[WebSocketServerProtocol, str] = {}
        self.node_sockets: Dict[str, WebSocketServerProtocol] = {}
        self.auth_challenges: Dict[
            WebSocketServerProtocol,
            tuple[str, str, float, str, str],
        ] = {}
        self.pending_by_node: Dict[str, Deque[tuple[float, str]]] = {}
        self.seen_frame_ids: Dict[str, float] = {}

    async def register(self, websocket: WebSocketServerProtocol) -> bool:
        async with self.lock:
            if len(self.clients) >= self.config.max_clients:
                return False
            self.clients.add(websocket)
            self.rate_windows[websocket] = deque()
        logging.info("Client connected: %s (total=%d)", websocket.remote_address, len(self.clients))
        return True

    async def unregister(self, websocket: WebSocketServerProtocol) -> None:
        async with self.lock:
            self.clients.discard(websocket)
            self.rate_windows.pop(websocket, None)
            self.auth_challenges.pop(websocket, None)
            node_id = self.auth_nodes.pop(websocket, None)
            if node_id and self.node_sockets.get(node_id) is websocket:
                self.node_sockets.pop(node_id, None)
        logging.info("Client disconnected: %s (total=%d)", websocket.remote_address, len(self.clients))

    async def allow_frame(self, websocket: WebSocketServerProtocol) -> bool:
        now = time.monotonic()
        async with self.lock:
            window = self.rate_windows.get(websocket)
            if window is None:
                return False
            while window and now - window[0] >= 60:
                window.popleft()
            if len(window) >= self.config.max_frames_per_minute:
                return False
            window.append(now)
            return True

    async def route_frame(
        self,
        sender: WebSocketServerProtocol,
        message: str,
        recipient_node_id: str,
    ) -> None:
        async with self.lock:
            if recipient_node_id:
                target = self.node_sockets.get(recipient_node_id)
                if target is None or target is sender:
                    queue = self.pending_by_node.setdefault(recipient_node_id, deque())
                    queue.append((time.monotonic() + QUEUE_TTL_SECONDS, message))
                    while len(queue) > 128:
                        queue.popleft()
                    logging.info(
                        "Frame queued for recipient=%s queue_size=%d",
                        recipient_node_id,
                        len(queue),
                    )
                    return
                peers = [target]
            else:
                peers = [
                    client for client in self.clients
                    if client is not sender and client in self.auth_nodes
                ]
        if not peers:
            return
        logging.info("Frame forwarded to %d authenticated client(s)", len(peers))
        await asyncio.gather(*(client.send(message) for client in peers), return_exceptions=True)

    async def _remember_frame(self, frame_id: str) -> bool:
        now = time.monotonic()
        async with self.lock:
            self.seen_frame_ids = {
                key: seen_at
                for key, seen_at in self.seen_frame_ids.items()
                if now - seen_at < QUEUE_TTL_SECONDS
            }
            if frame_id in self.seen_frame_ids:
                return False
            self.seen_frame_ids[frame_id] = now
            return True

    async def handle(self, websocket: WebSocketServerProtocol) -> None:
        if self.config.path and self._websocket_path(websocket) != self.config.path:
            await websocket.close(code=1008, reason="Invalid path")
            return

        if not await self.register(websocket):
            await websocket.close(code=1013, reason="Relay is at capacity")
            return
        try:
            async for message in websocket:
                if not isinstance(message, str):
                    continue
                if websocket not in self.auth_nodes:
                    if not await self._handle_auth_message(websocket, message):
                        logging.warning("Authentication rejected: %s", websocket.remote_address)
                        await websocket.close(code=1008, reason="Authentication required")
                        break
                    continue
                try:
                    heartbeat = json.loads(message)
                except (TypeError, json.JSONDecodeError):
                    heartbeat = None
                if isinstance(heartbeat, dict) and heartbeat.get("type") == RELAY_PING_TYPE:
                    await websocket.send(json.dumps({"type": RELAY_PONG_TYPE}, separators=(",", ":")))
                    continue
                logging.info(
                    "Message received from node=%s chars=%d",
                    self.auth_nodes.get(websocket, "unknown"),
                    len(message),
                )
                frame = self._parse_frame(message, self.config.max_payload_size)
                if frame is None:
                    self._log_invalid_frame(message)
                    continue
                if not await self.allow_frame(websocket):
                    await websocket.close(code=1013, reason="Rate limit exceeded")
                    break
                if frame["viaNodeId"] != self.auth_nodes.get(websocket):
                    await websocket.close(code=1008, reason="Node identity mismatch")
                    break
                if not await self._remember_frame(frame["frameId"]):
                    continue
                await self.route_frame(websocket, message, frame.get("recipientNodeId", ""))
        except websockets.ConnectionClosed:
            pass
        finally:
            await self.unregister(websocket)

    async def _handle_auth_message(self, websocket: WebSocketServerProtocol, raw: str) -> bool:
        try:
            data = json.loads(raw)
        except (TypeError, json.JSONDecodeError):
            return False
        if not isinstance(data, dict):
            return False
        message_type = data.get("type")
        if message_type == AUTH_HELLO_TYPE:
            return await self._handle_auth_hello(websocket, data)
        if message_type == AUTH_RESPONSE_TYPE:
            return await self._handle_auth_response(websocket, data)
        return False

    async def _handle_auth_hello(self, websocket: WebSocketServerProtocol, data: dict) -> bool:
        node_id = data.get("nodeId")
        public_key = data.get("signingPublicKey")
        if not self._is_bounded_string(node_id, MAX_NODE_ID_LENGTH):
            return False
        if not self._is_bounded_string(public_key, MAX_PUBLIC_KEY_LENGTH):
            return False
        if self.config.admission_token and data.get("admissionToken") != self.config.admission_token:
            return False
        try:
            key = serialization.load_der_public_key(base64.b64decode(public_key, validate=True))
        except (ValueError, TypeError, binascii.Error):
            return False
        if not isinstance(key, ec.EllipticCurvePublicKey):
            return False
        session_id = secrets.token_urlsafe(18)
        challenge = base64.b64encode(os.urandom(32)).decode("ascii")
        expires_at = time.time() + AUTH_CHALLENGE_TTL_SECONDS
        async with self.lock:
            self.auth_challenges[websocket] = (
                session_id,
                challenge,
                expires_at,
                node_id,
                public_key,
            )
        await websocket.send(json.dumps({
            "type": AUTH_CHALLENGE_TYPE,
            "sessionId": session_id,
            "challengeBase64": challenge,
            "expiresAtMs": int(expires_at * 1000),
        }, separators=(",", ":")))
        return True

    async def _handle_auth_response(self, websocket: WebSocketServerProtocol, data: dict) -> bool:
        session_id = data.get("sessionId")
        node_id = data.get("nodeId")
        public_key_b64 = data.get("signingPublicKey")
        signature_b64 = data.get("signatureBase64")
        if not self._is_bounded_string(session_id, 96):
            return False
        if not self._is_bounded_string(node_id, MAX_NODE_ID_LENGTH):
            return False
        if not self._is_bounded_string(public_key_b64, MAX_PUBLIC_KEY_LENGTH):
            return False
        if not self._is_bounded_string(signature_b64, MAX_SIGNATURE_LENGTH):
            return False
        async with self.lock:
            challenge = self.auth_challenges.get(websocket)
        if challenge is None:
            return False
        expected_session, challenge_b64, expires_at, expected_node_id, expected_public_key = challenge
        if session_id != expected_session or time.time() > expires_at:
            return False
        if node_id != expected_node_id or public_key_b64 != expected_public_key:
            return False
        signing_payload = "|".join([
            "MESH_RELAY_AUTH_V1",
            session_id,
            challenge_b64,
            node_id,
            public_key_b64,
        ]).encode("utf-8")
        try:
            public_key = serialization.load_der_public_key(
                base64.b64decode(public_key_b64, validate=True)
            )
            signature = base64.b64decode(signature_b64, validate=True)
            if not isinstance(public_key, ec.EllipticCurvePublicKey):
                return False
            public_key.verify(signature, signing_payload, ec.ECDSA(hashes.SHA256()))
        except (ValueError, TypeError, binascii.Error, InvalidSignature):
            return False
        replaced_socket = None
        async with self.lock:
            existing = self.node_sockets.get(node_id)
            if existing is not None and existing is not websocket:
                self.auth_nodes.pop(existing, None)
                replaced_socket = existing
            self.auth_nodes[websocket] = node_id
            self.node_sockets[node_id] = websocket
            self.auth_challenges.pop(websocket, None)
            queued = self.pending_by_node.pop(node_id, deque())
        if replaced_socket is not None:
            logging.info("Replacing stale connection for node=%s", node_id)
            # Do not block the new authenticated session on a half-open VPN
            # connection.  OkHttp/Cloudflare may need several seconds to notice
            # the old TCP path is gone.
            asyncio.create_task(
                replaced_socket.close(code=4001, reason="New authenticated session")
            )
        await websocket.send(json.dumps({
            "type": AUTH_ACCEPTED_TYPE,
            "nodeId": node_id,
            "expiresAtMs": int((time.time() + 24 * 60 * 60) * 1000),
        }, separators=(",", ":")))
        logging.info("Client authenticated: %s (total=%d)", node_id, len(self.clients))
        now = time.monotonic()
        queued_messages = [message for expires, message in queued if expires > now]
        if queued_messages:
            logging.info("Delivering queued frames to node=%s count=%d", node_id, len(queued_messages))
            await asyncio.gather(*(websocket.send(message) for message in queued_messages), return_exceptions=True)
        return True

    @staticmethod
    def _websocket_path(websocket: WebSocketServerProtocol) -> Optional[str]:
        """Support both the legacy and the new websockets connection APIs."""
        path = getattr(websocket, "path", None)
        if path is not None:
            return path
        request = getattr(websocket, "request", None)
        return getattr(request, "path", None)

    @staticmethod
    def _parse_frame(raw: str, max_payload_size: int) -> Optional[dict]:
        max_envelope_size = max(1024, max_payload_size * 2 + FRAME_OVERHEAD_BYTES)
        if len(raw.encode("utf-8")) > max_envelope_size:
            return None
        try:
            data = json.loads(raw)
        except (TypeError, json.JSONDecodeError):
            return None
        if not isinstance(data, dict) or data.get("type") != FRAME_TYPE:
            return None
        frame_id = data.get("frameId")
        via_node_id = data.get("viaNodeId")
        recipient_node_id = data.get("recipientNodeId", "")
        payload = data.get("payloadBase64")
        if not RelayHub._is_bounded_string(frame_id, 96):
            return None
        if not RelayHub._is_bounded_string(via_node_id, MAX_NODE_ID_LENGTH):
            return None
        if not RelayHub._is_bounded_string(recipient_node_id, MAX_NODE_ID_LENGTH, allow_blank=True):
            return None
        if not isinstance(payload, str) or not payload:
            return None
        try:
            decoded = base64.b64decode(payload, validate=True)
        except (ValueError, binascii.Error):
            return None
        if not 0 < len(decoded) <= max_payload_size:
            return None
        return data

    @staticmethod
    def _is_valid_frame(raw: str, max_payload_size: int) -> bool:
        return RelayHub._parse_frame(raw, max_payload_size) is not None

    @staticmethod
    def _log_invalid_frame(raw: str) -> None:
        """Log only envelope metadata; never log encrypted payload contents."""
        try:
            data = json.loads(raw)
        except (TypeError, json.JSONDecodeError):
            logging.warning("Invalid relay frame: malformed JSON length=%d", len(raw))
            return
        if not isinstance(data, dict):
            logging.warning("Invalid relay frame: non-object JSON length=%d", len(raw))
            return
        payload = data.get("payloadBase64")
        payload_length = len(payload) if isinstance(payload, str) else -1
        logging.warning(
            "Invalid relay frame: type=%r frame_id_length=%d via=%r recipient=%r "
            "payload_chars=%d envelope_chars=%d",
            data.get("type"),
            len(data.get("frameId")) if isinstance(data.get("frameId"), str) else -1,
            data.get("viaNodeId"),
            data.get("recipientNodeId", ""),
            payload_length,
            len(raw),
        )

    @staticmethod
    def _is_bounded_string(value: object, max_length: int, allow_blank: bool = False) -> bool:
        if not isinstance(value, str):
            return False
        if not allow_blank and not value:
            return False
        return len(value) <= max_length


async def run_server(config: Config) -> None:
    hub = RelayHub(config)
    async with websockets.serve(
        hub.handle,
        config.host,
        config.port,
        max_size=config.max_message_size,
        ping_interval=20,
        ping_timeout=20,
    ):
        logging.info("Relay listening on ws://%s:%d%s", config.host, config.port, config.path)
        await asyncio.Future()


def parse_args() -> Config:
    parser = argparse.ArgumentParser(description="MeshGram hybrid relay server")
    parser.add_argument("--host", default="0.0.0.0", help="Bind host")
    parser.add_argument("--port", type=int, default=8787, help="Bind port")
    parser.add_argument("--path", default="/ws", help="WebSocket path")
    parser.add_argument(
        "--max-message-size",
        type=int,
        default=2 * 1024 * 1024,
        help="Max frame size in bytes",
    )
    parser.add_argument("--max-clients", type=int, default=1000)
    parser.add_argument("--max-frames-per-minute", type=int, default=120)
    parser.add_argument("--max-payload-size", type=int, default=256 * 1024)
    parser.add_argument(
        "--admission-token",
        default=os.environ.get("MESHGRAM_RELAY_ADMISSION_TOKEN", ""),
        help="Optional enrollment token for first device registration",
    )
    args = parser.parse_args()
    return Config(
        host=args.host,
        port=args.port,
        path=args.path,
        max_message_size=args.max_message_size,
        max_clients=max(1, args.max_clients),
        max_frames_per_minute=max(1, args.max_frames_per_minute),
        max_payload_size=max(1, args.max_payload_size),
        admission_token=args.admission_token,
    )


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    config = parse_args()
    asyncio.run(run_server(config))


if __name__ == "__main__":
    main()
