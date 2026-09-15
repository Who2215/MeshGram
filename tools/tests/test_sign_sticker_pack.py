import base64
import hashlib
import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "sign_sticker_pack.py"
SPEC = importlib.util.spec_from_file_location("sign_sticker_pack", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class StickerPackSignerTest(unittest.TestCase):
    def test_canonical_payload_is_stable_and_utf8_length_prefixed(self):
        manifest = {
            "schemaVersion": 1,
            "packId": "community.fun",
            "version": 1,
            "title": "Community Fun",
            "attribution": "MeshGram test pack",
            "licenseUrl": "https://example.test/license",
            "stickers": [{
                "id": "community.fun/laugh",
                "label": "Laugh",
                "category": "reactions",
                "kind": "LOTTIE",
                "assetUrl": "https://example.test/stickers/laugh.json",
                "previewUrl": "https://example.test/stickers/laugh.png",
                "assetSha256": "a" * 64,
                "previewSha256": "b" * 64,
                "assetBytes": 100,
                "previewBytes": 80,
                "width": 128,
                "height": 128,
                "durationMs": 2000,
                "frameRate": 30,
            }],
        }
        digest = hashlib.sha256(MODULE.canonical_payload(manifest).encode("utf-8")).hexdigest()
        self.assertEqual("af7ba4104cbb8c50feaaacb305621bd8d010b1a10b745779057100668137a928", digest)

    def test_rejects_non_https_url(self):
        with self.assertRaises(ValueError):
            MODULE.require_https("http://example.test/file", "assetUrl")

    def test_png_header_rejects_decompression_bomb_dimensions(self):
        raw = bytearray(base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        ))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "preview.png"
            path.write_bytes(raw)
            self.assertEqual((1, 1), MODULE.png_header(path, 512))
            raw[16:20] = (4096).to_bytes(4, "big")
            path.write_bytes(raw)
            with self.assertRaises(ValueError):
                MODULE.png_header(path, 512)

    def test_lottie_complexity_is_bounded(self):
        with self.assertRaises(ValueError):
            MODULE.json_complexity([0] * 50_001)


if __name__ == "__main__":
    unittest.main()
