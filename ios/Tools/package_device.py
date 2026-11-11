"""Package only this project's unsigned, thin arm64 iPhone application."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import struct
import tempfile
import zipfile


def device_binary(path, minimum):
    with path.open("rb") as stream:
        header = stream.read(32)
        if len(header) != 32:
            raise ValueError("Truncated Mach-O header")
        magic, cpu, _, kind, count, size, _, _ = struct.unpack("<8I", header)
        if (magic, cpu, kind) != (0xFEEDFACF, 0x0100000C, 2):
            raise ValueError("Expected a thin arm64 Mach-O executable, not a simulator")
        if not 1 <= count <= 4096 or size > 4 * 1024 * 1024:
            raise ValueError("Invalid Mach-O command budget")
        commands = stream.read(size)
        if len(commands) != size:
            raise ValueError("Truncated Mach-O commands")
    offset, platforms = 0, []
    for _ in range(count):
        if offset + 8 > size:
            raise ValueError("Truncated Mach-O command")
        command, length = struct.unpack_from("<2I", commands, offset)
        if length < 8 or length % 8 or offset + length > size:
            raise ValueError("Invalid Mach-O command length")
        if command == 0x1D:
            raise ValueError("Signed executable is outside unsigned packaging scope")
        if command == 0x32:
            if length < 24:
                raise ValueError("Truncated build-version command")
            platform, minos, _, tools = struct.unpack_from("<4I", commands, offset + 8)
            if 24 + tools * 8 > length:
                raise ValueError("Truncated build-tool versions")
            platforms.append((platform, minos))
        offset += length
    if offset != size or platforms != [(2, minimum)]:
        raise ValueError("Expected one iPhoneOS build version matching Info.plist")


def package_app(archive, output):
    archive, output = Path(archive), Path(output)
    app = archive / "Products" / "Applications" / "MeshGram.app"
    if app.is_symlink() or not app.is_dir():
        raise ValueError("Missing application in Xcode device archive")
    paths = sorted(app.rglob("*"))
    for path in paths:
        if path.is_symlink() or not (path.is_file() or path.is_dir()):
            raise ValueError("Links and special files are not supported")
        if path.name == "embedded.mobileprovision" or "_CodeSignature" in path.parts:
            raise ValueError("Provisioning or signatures must remain local to the owner")
        if path.suffix.lower() in {".p12", ".p8", ".key"}:
            raise ValueError("Private signing material is forbidden")
    with (app / "Info.plist").open("rb") as stream:
        info = plistlib.load(stream)
    if (info.get("CFBundleSupportedPlatforms") != ["iPhoneOS"]
            or info.get("CFBundleExecutable") != "MeshGram"
            or info.get("CFBundleIdentifier") != "com.meshgram.ios"
            or info.get("CFBundlePackageType") != "APPL"
            or info.get("LSRequiresIPhoneOS") is not True):
        raise ValueError("Unexpected device application metadata")
    version = str(info.get("MinimumOSVersion", ""))
    parts = version.split(".")
    if not 1 <= len(parts) <= 3 or not all(part.isdecimal() for part in parts):
        raise ValueError("Invalid minimum iOS version")
    major, minor, patch = (list(map(int, parts)) + [0, 0])[:3]
    if not 16 <= major <= 65535 or max(minor, patch) > 255:
        raise ValueError("Unsupported minimum iOS version")
    device_binary(app / "MeshGram", (major << 16) | (minor << 8) | patch)
    if output.suffix != ".ipa":
        raise ValueError("Output must be an .ipa file")
    # Publish a validated file atomically without replacing an owner's artifact.
    with tempfile.TemporaryDirectory(prefix=".meshgram-ipa-", dir=output.parent) as folder:
        staged = Path(folder) / output.name
        with zipfile.ZipFile(staged, "x", compression=zipfile.ZIP_DEFLATED) as bundle:
            for path in paths:
                if path.is_file():
                    bundle.write(path, "Payload/MeshGram.app/" + path.relative_to(app).as_posix())
        with zipfile.ZipFile(staged) as bundle:
            if bundle.testzip() is not None:
                raise ValueError("Archive CRC validation failed")
        result = {
            "schema_version": 1, "bundle_id": info["CFBundleIdentifier"],
            "architecture": "arm64", "platform": "iPhoneOS", "minimum_ios": version,
            "unsigned": True, "requires_owner_signing": True,
            "device_execution_verified": False, "bytes": staged.stat().st_size,
            "sha256": hashlib.sha256(staged.read_bytes()).hexdigest(),
        }
        os.link(staged, output)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        result = package_app(args.archive, args.output)
    except (ValueError, OSError, plistlib.InvalidFileException) as error:
        parser.exit(2, f"Packaging refused: {error}\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
