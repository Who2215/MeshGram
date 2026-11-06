#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ "$(uname -s)" != "Darwin" ]; then
    printf '%s\n' 'Для проверки Swift и сборки iPhone нужен Mac с Xcode. Windows-проверка не заменяет эту сборку.' >&2
    exit 3
fi
command -v xcodebuild >/dev/null
command -v swift >/dev/null
xcodebuild -version
swift test --package-path "$root"
xcodebuild -project "$root/MeshGram.xcodeproj" -scheme MeshGram \
    -destination 'generic/platform=iOS Simulator' -derivedDataPath "$root/DerivedData" \
    CODE_SIGNING_ALLOWED=NO build
