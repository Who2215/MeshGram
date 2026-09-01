import { DurableObject } from "cloudflare:workers";

const FRAME_TYPE = "MESH_RELAY_FRAME_V1";
const AUTH_HELLO_TYPE = "MESH_RELAY_AUTH_HELLO_V1";
const AUTH_CHALLENGE_TYPE = "MESH_RELAY_AUTH_CHALLENGE_V1";
const AUTH_RESPONSE_TYPE = "MESH_RELAY_AUTH_RESPONSE_V1";
const AUTH_ACCEPTED_TYPE = "MESH_RELAY_AUTH_ACCEPTED_V1";
const MAX_PAYLOAD_BYTES = 256 * 1024;
const MAX_ENVELOPE_BYTES = Math.ceil(MAX_PAYLOAD_BYTES * 4 / 3) + 512;
const MAX_ID_LENGTH = 96;
const MAX_PUBLIC_KEY_LENGTH = 2048;
const MAX_SIGNATURE_LENGTH = 256;
const MAX_CLIENTS = 1000;
const RATE_WINDOW_MS = 60_000;
const MAX_FRAMES_PER_WINDOW = 120;
const AUTH_CHALLENGE_TTL_MS = 30_000;
// Keep opaque frames available for offline recipients for up to 30 days.
const QUEUE_TTL_MS = 30 * 24 * 60 * 60 * 1000;

interface Env {
  RELAY_ROOM: DurableObjectNamespace<RelayRoom>;
  RELAY_ADMISSION_TOKEN?: string;
}

type RelayFrame = {
  type: string;
  frameId: string;
  payloadBase64: string;
  viaNodeId: string;
  recipientNodeId?: string;
  sentAtMs?: number;
};

type AuthHello = {
  type: string;
  nodeId: string;
  signingPublicKey: string;
  admissionToken?: string;
};

type AuthChallenge = {
  sessionId: string;
  challengeBase64: string;
  expiresAtMs: number;
  nodeId: string;
  signingPublicKey: string;
};

function isBoundedString(value: unknown, maxLength: number, allowBlank = false): value is string {
  return typeof value === "string" && value.length <= maxLength && (allowBlank || value.length > 0);
}

function parseFrame(raw: string): RelayFrame | null {
  if (new TextEncoder().encode(raw).byteLength > MAX_ENVELOPE_BYTES) return null;
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!value || typeof value !== "object") return null;
  const frame = value as Partial<RelayFrame>;
  if (frame.type !== FRAME_TYPE) return null;
  if (!isBoundedString(frame.frameId, MAX_ID_LENGTH)) return null;
  if (!isBoundedString(frame.viaNodeId, MAX_ID_LENGTH)) return null;
  if (!isBoundedString(frame.recipientNodeId ?? "", MAX_ID_LENGTH, true)) return null;
  if (!isBoundedString(frame.payloadBase64, MAX_ENVELOPE_BYTES)) return null;
  try {
    const decoded = atob(frame.payloadBase64);
    if (decoded.length === 0 || decoded.length > MAX_PAYLOAD_BYTES) return null;
  } catch {
    return null;
  }
  return frame as RelayFrame;
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

function authSigningPayload(challenge: AuthChallenge): Uint8Array {
  return new TextEncoder().encode([
    "MESH_RELAY_AUTH_V1",
    challenge.sessionId,
    challenge.challengeBase64,
    challenge.nodeId,
    challenge.signingPublicKey,
  ].join("|"));
}

function derToP1363(signature: Uint8Array): Uint8Array | null {
  // Android's SHA256withECDSA returns DER; WebCrypto expects fixed-width r||s.
  if (signature.length < 8 || signature[0] !== 0x30) return null;
  let offset = 1;
  const sequenceLength = signature[offset++];
  if (sequenceLength & 0x80 || sequenceLength !== signature.length - offset) return null;
  if (signature[offset++] !== 0x02) return null;
  const rLength = signature[offset++];
  if (!rLength || offset + rLength > signature.length) return null;
  const r = signature.slice(offset, offset + rLength);
  offset += rLength;
  if (signature[offset++] !== 0x02) return null;
  const sLength = signature[offset++];
  if (!sLength || offset + sLength !== signature.length) return null;
  const s = signature.slice(offset, offset + sLength);
  const output = new Uint8Array(64);
  if (!copyDerInteger(r, output, 0) || !copyDerInteger(s, output, 32)) return null;
  return output;
}

function copyDerInteger(integer: Uint8Array, output: Uint8Array, targetOffset: number): boolean {
  let start = 0;
  while (start < integer.length - 1 && integer[start] === 0) start += 1;
  const value = integer.slice(start);
  if (value.length > 32) return false;
  output.set(value, targetOffset + 32 - value.length);
  return true;
}

async function importSigningKey(publicKeyBase64: string): Promise<CryptoKey | null> {
  try {
    return await crypto.subtle.importKey(
      "spki",
      base64ToBytes(publicKeyBase64),
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
  } catch {
    return null;
  }
}

async function verifyAuthResponse(
  key: CryptoKey,
  signatureBase64: string,
  challenge: AuthChallenge,
): Promise<boolean> {
  try {
    const p1363Signature = derToP1363(base64ToBytes(signatureBase64));
    if (!p1363Signature) return false;
    return await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      key,
      p1363Signature,
      authSigningPayload(challenge),
    );
  } catch {
    return false;
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname !== "/ws") return new Response("MeshGram relay", { status: 200 });
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("WebSocket upgrade required", { status: 426 });
    }
    const id = env.RELAY_ROOM.idFromName("global");
    return env.RELAY_ROOM.get(id).fetch(request);
  },
};

export class RelayRoom extends DurableObject<Env> {
  private readonly recentFrames = new Map<WebSocket, number[]>();
  private readonly authNodes = new Map<WebSocket, { nodeId: string; signingPublicKey: string; key: CryptoKey }>();
  private readonly nodeSockets = new Map<string, WebSocket>();
  private readonly challenges = new Map<WebSocket, AuthChallenge>();
  private readonly pendingByNode = new Map<string, Array<{ expiresAtMs: number; message: string }>>();
  private readonly seenFrameIds = new Map<string, number>();

  async fetch(request: Request): Promise<Response> {
    const pair = new WebSocketPair();
    if (this.ctx.getWebSockets().length >= MAX_CLIENTS) {
      return new Response("Relay capacity reached", { status: 503 });
    }
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server);
    this.recentFrames.set(server, []);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(sender: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string") return;
    const auth = this.authNodes.get(sender);
    if (!auth) {
      await this.handleAuthentication(sender, message);
      return;
    }
    const frame = parseFrame(message);
    if (!frame) return;
    if (frame.viaNodeId !== auth.nodeId) {
      sender.close(1008, "node identity mismatch");
      return;
    }
    if (!this.allowFrame(sender)) {
      sender.close(1013, "rate limit");
      return;
    }
    const now = Date.now();
    for (const [frameId, seenAtMs] of this.seenFrameIds) {
      if (now - seenAtMs >= QUEUE_TTL_MS) this.seenFrameIds.delete(frameId);
    }
    if (this.seenFrameIds.has(frame.frameId)) return;
    this.seenFrameIds.set(frame.frameId, now);
    await this.routeFrame(sender, message, frame.recipientNodeId ?? "");
  }

  private allowFrame(sender: WebSocket): boolean {
    const now = Date.now();
    const recent = (this.recentFrames.get(sender) ?? []).filter(
      (timestamp) => now - timestamp < RATE_WINDOW_MS,
    );
    if (recent.length >= MAX_FRAMES_PER_WINDOW) return false;
    recent.push(now);
    this.recentFrames.set(sender, recent);
    return true;
  }

  private async handleAuthentication(sender: WebSocket, raw: string): Promise<void> {
    let value: unknown;
    try {
      value = JSON.parse(raw);
    } catch {
      sender.close(1008, "authentication required");
      return;
    }
    if (!value || typeof value !== "object") {
      sender.close(1008, "authentication required");
      return;
    }
    const messageType = (value as { type?: unknown }).type;
    if (messageType === AUTH_HELLO_TYPE) {
      await this.handleAuthHello(sender, value as Partial<AuthHello>);
    } else if (messageType === AUTH_RESPONSE_TYPE) {
      await this.handleAuthResponse(sender, value as Record<string, unknown>);
    } else {
      sender.close(1008, "authentication required");
    }
  }

  private async handleAuthHello(sender: WebSocket, hello: Partial<AuthHello>): Promise<void> {
    if (!isBoundedString(hello.nodeId, MAX_ID_LENGTH)) return sender.close(1008, "invalid node id");
    if (!isBoundedString(hello.signingPublicKey, MAX_PUBLIC_KEY_LENGTH)) {
      return sender.close(1008, "invalid signing key");
    }
    if (this.env.RELAY_ADMISSION_TOKEN && hello.admissionToken !== this.env.RELAY_ADMISSION_TOKEN) {
      return sender.close(1008, "admission denied");
    }
    const key = await importSigningKey(hello.signingPublicKey);
    if (!key) return sender.close(1008, "invalid signing key");
    const existing = this.nodeSockets.get(hello.nodeId);
    if (existing && existing !== sender) return sender.close(1008, "node already connected");
    const challenge: AuthChallenge = {
      sessionId: crypto.randomUUID(),
      challengeBase64: bytesToBase64(crypto.getRandomValues(new Uint8Array(32))),
      expiresAtMs: Date.now() + AUTH_CHALLENGE_TTL_MS,
      nodeId: hello.nodeId,
      signingPublicKey: hello.signingPublicKey,
    };
    this.challenges.set(sender, challenge);
    sender.send(JSON.stringify({
      type: AUTH_CHALLENGE_TYPE,
      sessionId: challenge.sessionId,
      challengeBase64: challenge.challengeBase64,
      expiresAtMs: challenge.expiresAtMs,
    }));
  }

  private async handleAuthResponse(sender: WebSocket, response: Record<string, unknown>): Promise<void> {
    const challenge = this.challenges.get(sender);
    const sessionId = response.sessionId;
    const nodeId = response.nodeId;
    const publicKey = response.signingPublicKey;
    const signature = response.signatureBase64;
    if (!challenge || Date.now() > challenge.expiresAtMs) return sender.close(1008, "challenge expired");
    if (!isBoundedString(sessionId, MAX_ID_LENGTH) || sessionId !== challenge.sessionId) {
      return sender.close(1008, "invalid session");
    }
    if (!isBoundedString(nodeId, MAX_ID_LENGTH) || nodeId !== challenge.nodeId) {
      return sender.close(1008, "invalid node id");
    }
    if (!isBoundedString(publicKey, MAX_PUBLIC_KEY_LENGTH) || publicKey !== challenge.signingPublicKey) {
      return sender.close(1008, "invalid signing key");
    }
    if (!isBoundedString(signature, MAX_SIGNATURE_LENGTH)) return sender.close(1008, "invalid signature");
    const key = await importSigningKey(publicKey);
    if (!key || !(await verifyAuthResponse(key, signature, challenge))) {
      return sender.close(1008, "authentication failed");
    }
    const existing = this.nodeSockets.get(nodeId);
    if (existing && existing !== sender) return sender.close(1008, "node already connected");
    this.authNodes.set(sender, { nodeId, signingPublicKey: publicKey, key });
    this.nodeSockets.set(nodeId, sender);
    this.challenges.delete(sender);
    sender.send(JSON.stringify({
      type: AUTH_ACCEPTED_TYPE,
      nodeId,
      expiresAtMs: Date.now() + 24 * 60 * 60 * 1000,
    }));
    const queued = this.pendingByNode.get(nodeId) ?? [];
    this.pendingByNode.delete(nodeId);
    for (const item of queued) {
      if (item.expiresAtMs > Date.now()) sender.send(item.message);
    }
  }

  private async routeFrame(sender: WebSocket, message: string, recipientNodeId: string): Promise<void> {
    if (recipientNodeId) {
      const target = this.nodeSockets.get(recipientNodeId);
      if (!target || target === sender) {
        const queue = (this.pendingByNode.get(recipientNodeId) ?? []).filter(
          (item) => item.expiresAtMs > Date.now(),
        );
        queue.push({ expiresAtMs: Date.now() + QUEUE_TTL_MS, message });
        while (queue.length > 128) queue.shift();
        this.pendingByNode.set(recipientNodeId, queue);
        return;
      }
      try {
        target.send(message);
      } catch {
        try { target.close(1011, "send failed"); } catch { /* best effort */ }
      }
      return;
    }
    for (const peer of this.ctx.getWebSockets()) {
      if (peer === sender || !this.authNodes.has(peer)) continue;
      try {
        peer.send(message);
      } catch {
        try { peer.close(1011, "send failed"); } catch { /* best effort */ }
      }
    }
  }

  private removeSocket(socket: WebSocket): void {
    this.recentFrames.delete(socket);
    this.challenges.delete(socket);
    const auth = this.authNodes.get(socket);
    this.authNodes.delete(socket);
    if (auth && this.nodeSockets.get(auth.nodeId) === socket) this.nodeSockets.delete(auth.nodeId);
  }

  async webSocketClose(socket: WebSocket): Promise<void> {
    this.removeSocket(socket);
  }

  async webSocketError(socket: WebSocket): Promise<void> {
    this.removeSocket(socket);
  }
}
