import hashlib
from pathlib import Path
import plistlib
import struct
import tempfile
import unittest
from unittest import mock
import zipfile

from package_device import package_app


class DevicePackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="meshgram-device-package-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / "Device.xcarchive"
        self.app = self.archive / "Products/Applications/MeshGram.app"
        self.app.mkdir(parents=True)
        self.output = self.root / "MeshGram-unsigned.ipa"
        self.info = {
            "CFBundleSupportedPlatforms": ["iPhoneOS"],
            "CFBundleExecutable": "MeshGram", "CFBundleIdentifier": "com.meshgram.ios",
            "CFBundlePackageType": "APPL", "LSRequiresIPhoneOS": True,
            "MinimumOSVersion": "16.0",
        }
        self.write_info()
        self.write_binary()

    def write_info(self):
        (self.app / "Info.plist").write_bytes(plistlib.dumps(self.info))

    def write_binary(self, platform=2, cpu=0x0100000C, minos=16 << 16, signed=False):
        commands = struct.pack("<6I", 0x32, 24, platform, minos, 0x120500, 0)
        if signed:
            commands += struct.pack("<4I", 0x1D, 16, 0, 0)
        header = struct.pack("<8I", 0xFEEDFACF, cpu, 0, 2, 2 if signed else 1,
                             len(commands), 0, 0)
        (self.app / "MeshGram").write_bytes(header + commands + b"test-only-fixture")

    def test_payload_and_manifest(self):
        result = package_app(self.archive, self.output)
        with zipfile.ZipFile(self.output) as bundle:
            self.assertEqual(set(bundle.namelist()), {
                "Payload/MeshGram.app/Info.plist", "Payload/MeshGram.app/MeshGram"})
            self.assertIsNone(bundle.testzip())
        self.assertEqual(result["sha256"], hashlib.sha256(self.output.read_bytes()).hexdigest())
        self.assertTrue(result["requires_owner_signing"])
        self.assertFalse(result["device_execution_verified"])

    def test_arm64_simulator_is_not_a_device_binary(self):
        self.write_binary(platform=7)
        with self.assertRaises(ValueError):
            package_app(self.archive, self.output)
        self.assertFalse(self.output.exists())

    def test_wrong_architecture(self):
        self.write_binary(cpu=0x01000007)
        with self.assertRaises(ValueError):
            package_app(self.archive, self.output)

    def test_metadata_cannot_disguise_simulator(self):
        self.info["CFBundleSupportedPlatforms"] = ["iPhoneSimulator"]
        self.write_info()
        with self.assertRaises(ValueError):
            package_app(self.archive, self.output)

    def test_minimum_version_must_match_executable(self):
        self.write_binary(minos=17 << 16)
        with self.assertRaises(ValueError):
            package_app(self.archive, self.output)

    def test_provisioning_and_keys_are_not_uploaded(self):
        for name in ("embedded.mobileprovision", "owner.p12", "owner.p8", "owner.key"):
            with self.subTest(name=name):
                path = self.app / name
                path.write_bytes(b"non-secret-test-fixture")
                with self.assertRaises(ValueError):
                    package_app(self.archive, self.output)
                path.unlink()
        self.assertFalse(self.output.exists())

    def test_signed_executable_is_refused(self):
        self.write_binary(signed=True)
        with self.assertRaises(ValueError):
            package_app(self.archive, self.output)

    def test_truncated_commands_are_refused(self):
        binary = self.app / "MeshGram"
        binary.write_bytes(binary.read_bytes()[:40])
        with self.assertRaises(ValueError):
            package_app(self.archive, self.output)

    def test_existing_artifact_is_never_overwritten(self):
        self.output.write_bytes(b"owner-artifact")
        with self.assertRaises(FileExistsError):
            package_app(self.archive, self.output)
        self.assertEqual(self.output.read_bytes(), b"owner-artifact")

    def test_failed_write_leaves_no_ipa(self):
        real_write = zipfile.ZipFile.write

        def interrupted_write(bundle, *args, **kwargs):
            real_write(bundle, *args, **kwargs)
            raise OSError("simulated write failure")

        for operation in ("write", "crc"):
            with self.subTest(operation=operation):
                output = self.root / f"failed-{operation}.ipa"
                patcher = (mock.patch.object(zipfile.ZipFile, "write", new=interrupted_write)
                           if operation == "write" else
                           mock.patch.object(zipfile.ZipFile, "testzip", return_value="bad-member"))
                with patcher:
                    with self.assertRaises((OSError, ValueError)):
                        package_app(self.archive, output)
                self.assertFalse(output.exists())
                self.assertEqual(list(self.root.glob(".meshgram-ipa-*")), [])
                result = package_app(self.archive, output)
                self.assertEqual(result["sha256"], hashlib.sha256(output.read_bytes()).hexdigest())

    def test_links_are_refused(self):
        target = self.root / "outside.txt"
        target.write_text("test", encoding="utf8")
        try:
            (self.app / "link").symlink_to(target)
        except OSError as error:
            self.skipTest(f"Host does not allow symlinks: {error}")
        with self.assertRaises(ValueError):
            package_app(self.archive, self.output)


if __name__ == "__main__":
    unittest.main()
