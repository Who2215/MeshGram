#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ "$(uname -s)" != "Darwin" ]; then
    printf '%s\n' 'Для сборки установочного файла iPhone нужен Mac с Xcode.' >&2
    exit 3
fi
if [ "$#" -ne 1 ]; then
    printf '%s\n' 'Укажи один новый абсолютный каталог для результата сборки.' >&2
    exit 2
fi
out=$1
case "$out" in /*) ;; *) exit 2 ;; esac
if [ -e "$out" ] || [ -L "$out" ]; then
    printf '%s\n' 'Каталог результата уже существует. Он оставлен без изменений.' >&2
    exit 2
fi
command -v xcodebuild >/dev/null
command -v python3 >/dev/null
python3 "$root/Tools/test_package_device.py"
umask 077
mkdir -- "$out"
xcodebuild -project "$root/MeshGram.xcodeproj" -scheme MeshGram -configuration Release \
    -sdk iphoneos -destination 'generic/platform=iOS' \
    -derivedDataPath "$out/DerivedData" -archivePath "$out/MeshGram.xcarchive" \
    CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO archive
python3 "$root/Tools/package_device.py" "$out/MeshGram.xcarchive" \
    "$out/MeshGram-unsigned.ipa" > "$out/manifest.json"
printf '%s\n' 'Файл iPhone собран без подписи. Для установки нужна подпись владельца.'
