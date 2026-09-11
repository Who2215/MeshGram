import base64
import time
import unittest

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from http_relay_server import FRAME_DEDUP_WINDOW_SECONDS, HttpRelayHub


class HttpRelayTests(unittest.TestCase):
    @staticmethod
    def _identity(node_id):
        private_key = ec.generate_private_key(ec.SECP256R1())
        public_key = base64.b64encode(
            private_key.public_key().public_bytes(
                serialization.Encoding.DER,
                serialization.PublicFormat.SubjectPublicKeyInfo,
            )
        ).decode("ascii")
        return node_id, private_key, public_key

    @staticmethod
    def _authenticate(hub, identity):
        node_id, private_key, public_key = identity
        challenge = hub.hello({
            "type": "MESH_RELAY_AUTH_HELLO_V1",
            "nodeId": node_id,
            "signingPublicKey": public_key,
        })
        signing_payload = "|".join([
            "MESH_RELAY_AUTH_V1",
            challenge["sessionId"],
            challenge["challengeBase64"],
            node_id,
            public_key,
        ]).encode("utf-8")
        signature = private_key.sign(signing_payload, ec.ECDSA(hashes.SHA256()))
        accepted = hub.authenticate({
            "type": "MESH_RELAY_AUTH_RESPONSE_V1",
            "sessionId": challenge["sessionId"],
            "nodeId": node_id,
            "signingPublicKey": public_key,
            "signatureBase64": base64.b64encode(signature).decode("ascii"),
        })
        return challenge["sessionId"], accepted

    def test_signed_auth_and_offline_queue(self):
        hub = HttpRelayHub()
        sender = self._identity("sender")
        recipient = self._identity("recipient")
        sender_session, accepted = self._authenticate(hub, sender)
        self.assertEqual("MESH_RELAY_AUTH_ACCEPTED_V1", accepted["type"])

        frame = {
            "type": "MESH_RELAY_FRAME_V1",
            "frameId": "frame-1",
            "viaNodeId": "sender",
            "recipientNodeId": "recipient",
            "payloadBase64": base64.b64encode(b"opaque-encrypted-frame").decode("ascii"),
            "sentAtMs": 1,
        }
        hub.publish(sender_session, frame)

        recipient_session, _ = self._authenticate(hub, recipient)
        self.assertEqual([frame], hub.poll(recipient_session))
        self.assertEqual([], hub.poll(recipient_session))

    def test_rejects_sender_mismatch(self):
        hub = HttpRelayHub()
        sender_session, _ = self._authenticate(hub, self._identity("sender"))
        with self.assertRaises(ValueError):
            hub.publish(sender_session, {
                "type": "MESH_RELAY_FRAME_V1",
                "frameId": "frame-2",
                "viaNodeId": "attacker",
                "payloadBase64": base64.b64encode(b"x").decode("ascii"),
                "sentAtMs": 1,
            })

    def test_requeues_after_retry_window_if_poll_response_was_lost(self):
        hub = HttpRelayHub()
        sender_session, _ = self._authenticate(hub, self._identity("sender"))
        recipient_session, _ = self._authenticate(hub, self._identity("recipient"))
        frame = {
            "type": "MESH_RELAY_FRAME_V1",
            "frameId": "frame-retry",
            "viaNodeId": "sender",
            "recipientNodeId": "recipient",
            "payloadBase64": base64.b64encode(b"opaque-encrypted-frame").decode("ascii"),
            "sentAtMs": 1,
        }

        hub.publish(sender_session, frame)
        self.assertEqual([frame], hub.poll(recipient_session))

        # Model the Android outbox retry after the first poll response was lost.
        hub.seen_frames[frame["frameId"]] = (
            time.time() - FRAME_DEDUP_WINDOW_SECONDS - 1
        )
        hub.publish(sender_session, frame)
        self.assertEqual([frame], hub.poll(recipient_session))

    def test_deduplicates_retry_while_frame_is_still_queued(self):
        hub = HttpRelayHub()
        sender_session, _ = self._authenticate(hub, self._identity("sender"))
        recipient_session, _ = self._authenticate(hub, self._identity("recipient"))
        frame = {
            "type": "MESH_RELAY_FRAME_V1",
            "frameId": "frame-pending-retry",
            "viaNodeId": "sender",
            "recipientNodeId": "recipient",
            "payloadBase64": base64.b64encode(b"opaque-encrypted-frame").decode("ascii"),
            "sentAtMs": 1,
        }

        hub.publish(sender_session, frame)
        hub.seen_frames[frame["frameId"]] = (
            time.time() - FRAME_DEDUP_WINDOW_SECONDS - 1
        )
        hub.publish(sender_session, frame)
        self.assertEqual([frame], hub.poll(recipient_session))

    def test_retired_session_grace_accepts_in_flight_request(self):
        hub = HttpRelayHub()
        sender = self._identity("sender")
        recipient_session, _ = self._authenticate(hub, self._identity("recipient"))
        old_session, _ = self._authenticate(hub, sender)
        self._authenticate(hub, sender)
        frame = {
            "type": "MESH_RELAY_FRAME_V1",
            "frameId": "frame-retired-session",
            "viaNodeId": "sender",
            "recipientNodeId": "recipient",
            "payloadBase64": base64.b64encode(b"opaque-encrypted-frame").decode("ascii"),
            "sentAtMs": 1,
        }

        hub.publish(old_session, frame)
        self.assertEqual([frame], hub.poll(recipient_session))


if __name__ == "__main__":
    unittest.main()
