# Security Review: mesh-messenger-android

## Scope

Static audit of MeshGram Android client, relay implementations, update verification, and static site.

- Scan mode: repository
- Target kind: directory_snapshot
- Target ID: target_sha256_4bd7f28c9f7c20d8630362194b15a718aa7972712e94cf5cab4f8838e896e28e
- Snapshot digest: codex-security-snapshot/v1:sha256:b8461d7baff0675ab8e8008640b61b2235bb9c65ca8ce789d2f33d569076421f
- Inventory strategy: directory
- Included paths: .
- Excluded paths: .gradle/\*\*, app/build/\*\*, relay/cloudflare-worker/node_modules/\*\*, hs_err_pid\*.log, replay_pid\*.log
- Runtime or test status: No public deployment available for runtime testing.
- Artifacts reviewed: app/src, relay, site, app/build.gradle.kts, RELEASE_SECURITY.md

Limitations and exclusions:
- The snapshot predates the final path and relay URL hardening edits.
- Third-party dependencies and generated output were excluded.
- Excluded app/build/\*\*: Generated APK/intermediates; product source was reviewed and APK was installed separately.
- Excluded relay/cloudflare-worker/node_modules/\*\*: Third-party installed dependencies; npm audit was run separately.

### Scan Summary

| Field | Value |
| --- | --- |
| Scan outcome | completed |
| Reportable findings | 2 |
| Severity mix | medium: 1, low: 1 |
| Confidence mix | high: 2 |
| Coverage | partial |
| Validation mode | Source-backed static review plus local protocol and build tests. |

Canonical artifacts: `scan-manifest.json`, `findings.json`, and `coverage.json`. This report is a deterministic projection of those files.

## Threat Model

MeshGram prefers BLE peer transport and uses an optional WebSocket relay for distant delivery. Message frames are signed and end-to-end encrypted by the client; the relay forwards opaque envelopes and the static site distributes test APKs.

### Assets

- Message and file plaintext
- Peer identity keys
- Encrypted local history
- Relay availability
- APK/update authenticity

### Trust Boundaries

- Untrusted BLE, LAN, and relay peers send serialized frames to Android.
- Relay clients share a global broadcast room.
- Update installation crosses into the system package installer.
- Static site content is public browser input.

### Attacker Capabilities

- Send malformed or protocol-shaped frames to reachable transports.
- Attempt arbitrary relay connections.
- Select a local backup file for import.
- No assumed control of AndroidKeyStore or owner signing keys.

### Security Objectives

- Only the intended endpoint decrypts content.
- Forged frames are rejected.
- File metadata cannot escape its storage root.
- Relay input is bounded.
- Updates require HTTPS, signature, hash, package, and certificate checks.

### Assumptions

- BLE is preferred and internet is fallback; other apps' network settings are not changed.
- Ordinary Bluetooth devices do not relay MeshGram packets.
- Current APK is a debug test build; owner signing remains pending.
- The final worktree contains fixes applied after this scan snapshot.

## Findings

| Finding | Severity | Confidence | Detailed write-up |
| --- | --- | --- | --- |
| [Relay accepts arbitrary clients without admission authentication](#finding-1) | medium | high | inline below |
| [Incoming transfer metadata could escape the attachment directory](#finding-2) | low | high | inline below |

### Confidence Scale

| Label | Meaning |
| --- | --- |
| high | Direct evidence supports the finding with no material unresolved blocker. |
| medium | Evidence supports a plausible issue, but material runtime or reachability proof remains. |
| low | Evidence is incomplete and the item is retained only for explicit follow-up. |

<a id="finding-1"></a>

### [1] Relay accepts arbitrary clients without admission authentication

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | The connection handlers have no authentication or authorization check before registration and broadcast. |
| Category | missing-authentication |
| CWE | CWE-306, CWE-400 |
| Affected lines | relay/relay_server.py:74-91, relay/cloudflare-worker/src/index.ts:49-57, relay/cloudflare-worker/src/index.ts:64-73 |

#### Summary

Both relay implementations accept a WebSocket connection and broadcast protocol-shaped frames without authenticating the connecting node or proving possession of a MeshGram identity.

#### Root Cause

Relay admission is modeled as transport connectivity rather than authenticated membership.

#### Validation

A party that can reach the WebSocket endpoint can occupy a slot and submit syntactically valid opaque frames. The server cannot distinguish a real MeshGram node from an arbitrary client.

Validation method: Source trace of both server entrypoints and broadcast path.

#### Dataflow

Unauthenticated WebSocket -\> register -\> bounded frame validation -\> broadcast to all peers.

#### Reachability

Reachable whenever a public relay endpoint is deployed; no public deployment was available in this workspace.

#### Severity

**Medium** — An internet-reachable deployment allows unaffiliated clients to consume connection capacity and send bounded frames to every connected client. End-to-end encryption limits plaintext exposure, but availability and traffic integrity remain affected.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** Relay availability degradation and unsolicited encrypted traffic to connected clients.

#### Remediation

Add an authenticated admission mechanism that does not expose message keys, such as a short-lived relay access token issued by the service or a proof-of-possession handshake bound to the node signing key. Keep the existing client, payload, per-client rate, and connection limits as defense in depth; add abuse monitoring and per-IP/account quotas at the edge.

<a id="finding-2"></a>

### [2] Incoming transfer metadata could escape the attachment directory

| Field | Value |
| --- | --- |
| Severity | low |
| Confidence | high |
| Confidence rationale | The snapshot source directly concatenated transferId into a File under attachmentsDir without canonical containment. |
| Category | path-traversal |
| CWE | CWE-22 |
| Affected lines | app/src/main/java/com/meshchat/app/mesh/SecureLocalStore.kt:171-181, app/src/main/java/com/meshchat/app/mesh/BleMeshManager.kt:4251-4290 |

#### Summary

The scan snapshot used a remote transfer identifier directly in the attachment filename before writing encrypted data.

#### Root Cause

Remote file metadata was treated as a safe path component without normalization and root containment.

#### Validation

A malicious peer could choose transferId values containing path separators in the pre-fix snapshot, reaching the local file write operation.

Validation method: Source-backed trace from incoming file chunk metadata to saveAttachment.

#### Dataflow

Signed packet metadata transferId -\> attachment filename -\> File write.

#### Reachability

Reachable from a malicious MeshGram peer able to send a valid encrypted packet in the scanned snapshot.

#### Severity

**Low** — The path was derived from an encrypted, signed packet but a malicious installed peer could choose its own transfer identifier. The impact is app-private file pollution or denial of service rather than code execution.

Additional runtime or deployment evidence could raise or lower this severity.

**Impact assessment:** Potential internal file pollution or application storage denial of service.

#### Remediation

Use a restricted filename token and canonical containment check for every attachment read/write. This remediation is present in the final 1.0.6 worktree and covered by the current build.

## Reviewed Surfaces

| Surface | Risk Area | Outcome | Notes |
| --- | --- | --- | --- |
| Android BLE/LAN/relay transport and frame parsing | message confidentiality and integrity | No issue found | Signatures, fingerprints, recipient checks, hop limits, AEAD, bounded file chunks, and relay-frame limits were reviewed. |
| Encrypted local storage and attachment paths | at-rest confidentiality and path safety | Reported | The scan snapshot contained the attachment path issue; the current worktree fixes it with token sanitization and canonical containment. |
| Update manifest, APK hash, package, and certificate checks | release authenticity | No issue found | Only HTTPS URLs, signed manifests, APK SHA-256, package name, and signing certificate are accepted before user-approved install. |
| Python WebSocket relay | admission and resource exhaustion | Reported | Frame size, Base64, client count, and per-client rate limits are enforced; admission authentication remains open. |
| Cloudflare Durable Object relay | admission and resource exhaustion | Reported | Frame validation, client count, and per-client rate limits are enforced; public deployment was not tested. |
| Static download site and browser assets | browser injection and download integrity | No issue found | No third-party scripts or secrets were found; CSP, framing, MIME, and referrer headers are present, and release hashes are published. |
| Public hosting, TLS, edge abuse controls, and update rollout | production exposure | Needs follow-up | No authenticated hosting or public endpoint was available for runtime testing. |

## Open Questions And Follow Up

- Which authenticated admission model should the production relay use: per-install token, account token, or proof-of-possession handshake?
  - Follow-up prompt: Choose the production relay admission model before exposing the endpoint publicly.
- What owner-controlled public hostname and TLS provider will host the relay and update manifest?
  - Follow-up prompt: Provide or authorize the hosting account and domain after deployment.
- Requires a deployed HTTPS/WSS endpoint and external account authorization.
  - Follow-up prompt: Review deferred unit public-relay-runtime and close its stated proof gap. Paths: relay/relay_server.py, relay/cloudflare-worker/src/index.ts. Surfaces: relay-python, relay-cloudflare, external-deployment.
- Requires the owner's keystore and certificate fingerprint.
  - Follow-up prompt: Review deferred unit owner-production-signing and close its stated proof gap. Paths: app/build.gradle.kts, RELEASE_SECURITY.md. Surfaces: android-updates.
