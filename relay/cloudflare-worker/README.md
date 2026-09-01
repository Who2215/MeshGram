# Cloudflare free relay

This is an optional public relay for the Android client. It forwards only
`MESH_RELAY_FRAME_V1` envelopes and never decrypts their payloads.

## Deploy

1. Create/sign in to a Cloudflare account and install Node.js dependencies:
   `npm install`
2. Authenticate Wrangler: `npx wrangler login`
3. Set the private enrollment secret: `npx wrangler secret put RELAY_ADMISSION_TOKEN`
4. Deploy: `npm run deploy`
5. Configure the Android app with `wss://<worker-subdomain>.workers.dev/ws`.

The Worker uses a SQLite-backed Durable Object and WebSocket hibernation,
which are available on the Workers Free plan subject to Cloudflare's current
quotas. The application remains BLE-first and connects to this endpoint only
when no ready MeshGram BLE route is available.

The Worker includes payload validation, a 1,000-connection cap, a per-connection rate
limit, a signed P-256 proof-of-possession handshake, exact-recipient routing, and a
bounded seven-day offline queue. Keep `RELAY_ADMISSION_TOKEN` configured as a secret
before publishing the endpoint widely. For production, add account-level abuse controls
and monitor quota usage.
