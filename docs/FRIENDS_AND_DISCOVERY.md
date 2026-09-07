# Friends and Optional Nearby Discovery

## User Flow

- Open Profile > Friends and nearby discovery, or Add friend on the map.
- Nearby discovery is off by default. Routing participants are not contacts.
- Share a personal QR/invite with a specific friend. The recipient verifies the
  signed identity and sends an encrypted request; the inviter explicitly accepts.
- Both ends retain the confirmed public-key fingerprint in encrypted local storage.
- Private chats and the map use confirmed contacts. Existing conversations remain
  on disk, but unconfirmed direct chats cannot send messages or attachments.
- Turning discovery off does not remove friends or disable encrypted relaying.

## Protocol Boundaries

Routing HELLO packets expose a technical node identifier and public keys, not the
personal alias or avatar. An opt-in public profile uses a signed version-2 HELLO
with zero relay hops on direct BLE links. Profiles are not forwarded to the
internet relay or Wi-Fi LAN by this discovery path.

Invites are signed, single-use, revocable and valid for 24 hours. Requests expire
after seven days. An unsolicited acceptance, changed fingerprint, blocked sender,
oversized profile or excess pending request does not create a friendship. Nearby
opt-in alone does not admit an uninvited internet request. Personal profile updates
travel inside the existing encrypted message envelope between confirmed friends.

This is not global offline username search or a location service. Delivery still
needs a working MeshGram route. Other Bluetooth devices do not automatically act
as relays. A name alone is not identity proof: verify the displayed fingerprint
with the intended person. Sharing an invite with a stranger does not automatically
accept them. Previously learned profile information cannot be remotely erased.

Both endpoints need this version for contact consent. Existing group membership
continues to authorize group traffic; this change gates direct conversations.
New-device migration requires confirming that device's identity again. The
existing portable chat backup does not grant a new device prior contact consent.

## Verification, 2026-09-07

- Debug and author-signed release APKs built as 1.0.10 (110).
- 26 JVM tests passed, including 13 consent-policy tests; Android lint passed.
- Updated Samsung S24 FE and Redmi Note 10 Pro in place with the matching test
  signing certificate. No uninstall, app-data reset or history deletion.
- On both phones discovery started off and strangers were absent from the map.
- Created an invite on Samsung, copied its code, imported it on Redmi, verified
  the preview, sent a request and accepted it on Samsung. Both showed friendship.
- Unique test messages were received Samsung-to-Redmi and Redmi-to-Samsung with
  the application's internet relay disabled on both phones.
- Force-stopped and reopened both apps: consent survived and discovery stayed off.
- Switched discovery on/off on both devices and restored it to off.
- No fatal application exception or consent-save error in the captured test logs.

Camera-based QR scanning, a third unconfirmed nearby device and long-distance
multi-hop delivery were not exercised in this physical two-phone run. The invite
import path was exercised using exactly the payload contained in the QR.

During device QA, atomic consent storage exposed an existing Keystore AES-GCM
fallback bug: caller-supplied IVs were forbidden by the key policy. Encryption now
uses the Keystore-generated IV; the persisted envelope/decryption format remains
compatible. Consent writes use AtomicFile so interrupted writes retain the prior
state. Runtime errors were rechecked after this fix.
