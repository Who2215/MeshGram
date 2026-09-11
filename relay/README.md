# MeshGram Hybrid Relay

Simple WebSocket fan-out relay for `MESH_RELAY_FRAME_V1` envelopes.

## Run

```bash
cd relay
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
  python relay_server.py --host 0.0.0.0 --port 8787 --path /ws --admission-token "<private-enrollment-token>"
```

## App URL example

- Local network: `ws://192.168.1.10:8787/ws`
- Public/TLS: `wss://your-domain/ws`

## Notes

- This relay does not decrypt content. It only forwards encrypted frames.
- MeshGram chooses BLE first and uses this relay only when no local MeshGram BLE route is available.
- The relay only forwards traffic between connected MeshGram clients. A random Bluetooth device cannot become a relay node.
- For public usage, place it behind HTTPS/WSS reverse proxy and add rate limiting.
- Before exposing a relay publicly, set a private admission token with `--admission-token` or `MESHGRAM_RELAY_ADMISSION_TOKEN`. The signed client handshake then binds every frame to its node key and exact recipient. The relay intentionally forwards opaque encrypted frames; client, payload, queue, and rate limits remain defense in depth.
- Offline delivery is bounded to 30 days and 128 queued frames per recipient. The relay stores only opaque encrypted envelopes and does not provide message decryption.

## Temporary PC test tunnel

For a short remote test without router port forwarding, keep the relay bound to
`127.0.0.1` and run Cloudflare's account-less quick tunnel in a second terminal:

```powershell
python relay_server.py --host 127.0.0.1 --port 8787 --path /ws
H:\mesh-workspace\tools\cloudflared\cloudflared.exe tunnel --no-autoupdate --url http://127.0.0.1:8787
```

Use the printed `wss://...trycloudflare.com/ws` address in MeshGram on both
phones. The address is temporary and changes when the tunnel process stops;
this mode is for testing, not a production deployment. No router port is
opened by this setup.

## HTTPS polling fallback

Some VPNs and mobile carriers handle long-lived WebSockets poorly. MeshGram
also supports the same signed handshake and opaque encrypted frames over
short HTTPS requests:

```powershell
python http_relay_server.py --host 127.0.0.1 --port 8788
H:\mesh-workspace\tools\cloudflared\cloudflared.exe tunnel --no-autoupdate --protocol http2 --url http://127.0.0.1:8788
```

Use the printed `https://...trycloudflare.com` address in MeshGram without a
`/ws` suffix. The app polls only when no nearby MeshGram BLE route exists.
This quick-tunnel address is temporary and must not be treated as a
production endpoint.

## Timeweb deployment

The current production test relay is available at:

```text
https://77-233-213-107.sslip.io
```

It is fronted by nginx on ports 80/443, with the Python relay bound to
127.0.0.1:8788 and a Let's Encrypt certificate. The Android client keeps the
relay opt-in; when enabled, it uses BLE first and this HTTPS relay only when a
nearby BLE route is unavailable. The relay queue is intentionally in-memory in
this first deployment, so a service restart drops queued frames.
