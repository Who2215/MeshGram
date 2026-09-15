import importlib.util
import sys
import unittest
from pathlib import Path


TOOLS = Path(__file__).parents[1]
sys.path.insert(0, str(TOOLS))
SPEC = importlib.util.spec_from_file_location("publish_noto_sticker_pack", TOOLS / "publish_noto_sticker_pack.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class NotoPackPublisherTest(unittest.TestCase):
    def test_preview_codepoint_removes_only_variation_selector(self):
        self.assertEqual("270c", MODULE.preview_codepoint("270c_fe0f"))
        self.assertEqual("1f426_200d_2b1b", MODULE.preview_codepoint("1f426_200d_2b1b"))

    def test_sha256_is_stable(self):
        self.assertEqual(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            MODULE.sha256(b"abc"),
        )


if __name__ == "__main__":
    unittest.main()
