import base64
import json
import unittest

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from relay_server import RelayHub, Config


class FakeWebSocket:
    def __init__(self):
        self.remote_address = ("test", 1)
        self.sent = []
        self.closed = []

    async def send(self, message):
        self.sent.append(message)

    async def close(self, code, reason):
        self.closed.append((code, reason))


class RelayValidationTests(unittest.TestCase):
    def setUp(self):
        self.config = Config(
            host="127.0.0.1",
            port=8787,
            path="/ws",
            max_message_size=1024,
            max_clients=2,
            max_frames_per_minute=2,
            max_payload_size=32,
        )

    def test_accepts_bounded_mesh_frame(self):
        frame = json.dumps({
            "type": "MESH_RELAY_FRAME_V1",
            "frameId": "frame-1",
            "viaNodeId": "node-1",
            "payloadBase64": base64.b64encode(b"encrypted").decode(),
        })
        self.assertTrue(RelayHub._is_valid_frame(frame, self.config.max_payload_size))

    def test_rejects_invalid_base64(self):
        frame = json.dumps({
            "type": "MESH_RELAY_FRAME_V1",
            "frameId": "frame-1",
            "viaNodeId": "node-1",
            "payloadBase64": "not-base64!!!",
        })
        self.assertFalse(RelayHub._is_valid_frame(frame, self.config.max_payload_size))

    def test_rejects_oversized_payload(self):
        frame = json.dumps({
            "type": "MESH_RELAY_FRAME_V1",
            "frameId": "frame-1",
            "viaNodeId": "node-1",
            "payloadBase64": base64.b64encode(b"x" * 33).decode(),
        })
        self.assertFalse(RelayHub._is_valid_frame(frame, self.config.max_payload_size))

    def test_recipient_is_bounded(self):
        frame = json.dumps({
            "type": "MESH_RELAY_FRAME_V1",
            "frameId": "frame-1",
            "viaNodeId": "node-1",
            "recipientNodeId": "x" * 97,
            "payloadBase64": base64.b64encode(b"encrypted").decode(),
        })
        self.assertFalse(RelayHub._is_valid_frame(frame, self.config.max_payload_size))

    def test_authentication_requires_proof_of_key_ownership(self):
        import asyncio

        async def exercise():
            hub = RelayHub(self.config)
            socket = FakeWebSocket()
            private_key = ec.generate_private_key(ec.SECP256R1())
            public_key = base64.b64encode(
                private_key.public_key().public_bytes(
                    serialization.Encoding.DER,
                    serialization.PublicFormat.SubjectPublicKeyInfo,
                )
            ).decode()
            hello = {
                "type": "MESH_RELAY_AUTH_HELLO_V1",
                "nodeId": "node-1",
                "signingPublicKey": public_key,
            }
            self.assertTrue(await hub._handle_auth_hello(socket, hello))
            challenge = json.loads(socket.sent[-1])
            signing_payload = "|".join([
                "MESH_RELAY_AUTH_V1",
                challenge["sessionId"],
                challenge["challengeBase64"],
                "node-1",
                public_key,
            ]).encode()
            signature = private_key.sign(signing_payload, ec.ECDSA(hashes.SHA256()))
            response = {
                "type": "MESH_RELAY_AUTH_RESPONSE_V1",
                "sessionId": challenge["sessionId"],
                "nodeId": "node-1",
                "signingPublicKey": public_key,
                "signatureBase64": base64.b64encode(signature).decode(),
            }
            self.assertTrue(await hub._handle_auth_response(socket, response))
            self.assertEqual(hub.auth_nodes[socket], "node-1")

            rejected_socket = FakeWebSocket()
            rejected_hello = dict(hello)
            rejected_hello["nodeId"] = "node-2"
            self.assertTrue(await hub._handle_auth_hello(rejected_socket, rejected_hello))
            rejected_challenge = json.loads(rejected_socket.sent[-1])
            bad_response = dict(response)
            bad_response["sessionId"] = rejected_challenge["sessionId"]
            bad_response["nodeId"] = "node-2"
            bad_response["signatureBase64"] = base64.b64encode(b"invalid").decode()
            self.assertFalse(await hub._handle_auth_response(rejected_socket, bad_response))
            self.assertNotIn(rejected_socket, hub.auth_nodes)

        asyncio.run(exercise())


if __name__ == "__main__":
    unittest.main()
