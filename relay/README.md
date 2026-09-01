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
