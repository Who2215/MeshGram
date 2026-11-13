#!/bin/bash
set -euo pipefail
if [[ "$(uname -s)" != Darwin || "${GITHUB_ACTIONS:-}" != true ]]; then
  printf '%s\n' 'Этот прогон разрешён только на изолированном Mac в CI.' >&2
  exit 3
fi
work=${1:?new output directory required}
export MESHGRAM_UI_WORK="$work"
export MESHGRAM_UI_ARCH="$(uname -m)"
case "$MESHGRAM_UI_ARCH" in x86_64|arm64) ;; *) exit 3 ;; esac
python3 - <<'PY'
import os
from pathlib import Path
root = Path(os.environ['RUNNER_TEMP']).resolve()
work = Path(os.environ['MESHGRAM_UI_WORK']).resolve()
assert work != root and work.is_relative_to(root) and not work.exists()
assert os.statvfs(root).f_bavail * os.statvfs(root).f_frsize >= 6 * 1024**3
work.mkdir()
(work / 'artifacts').mkdir()
PY
xcrun simctl list runtimes --json > "$work/runtimes.json"
xcrun simctl list devicetypes --json > "$work/types.json"
export MESHGRAM_UI_SDK="$(xcrun --sdk iphonesimulator --show-sdk-version)"
python3 - <<'PY'
import json, os, re
from pathlib import Path
work = Path(os.environ['MESHGRAM_UI_WORK'])
def version(value):
    assert re.fullmatch(r'\d+(\.\d+){0,2}', value)
    return tuple((list(map(int, value.split('.'))) + [0, 0])[:3])
sdk = version(os.environ['MESHGRAM_UI_SDK'])
available = [r for r in json.loads((work/'runtimes.json').read_text())['runtimes']
             if r.get('isAvailable') and '.iOS-' in r['identifier'] and (16, 0, 0) <= version(r['version']) <= sdk]
assert available, 'No installed supported iOS runtime; no downloads or skip-as-pass'
runtime = max(available, key=lambda r: version(r['version']))
types = json.loads((work/'types.json').read_text())['devicetypes']
device_type = next(t['identifier'] for t in types if t['identifier'] == 'com.apple.CoreSimulator.SimDeviceType.iPhone-12')
(work/'runtime-id.txt').write_text(runtime['identifier'])
(work/'type-id.txt').write_text(device_type)
(work/'selection.json').write_text(json.dumps({'runtime':runtime['identifier'], 'version':runtime['version'], 'sdk':os.environ['MESHGRAM_UI_SDK'], 'deviceType':device_type, 'architecture':os.environ['MESHGRAM_UI_ARCH']}))
PY
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
# Compile before boot so a cold simulator does not compete with the compiler.
xcodebuild -project "$root/MeshGram.xcodeproj" -scheme MeshGramUI \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$work/DerivedData" \
  ARCHS="$MESHGRAM_UI_ARCH" ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=YES \
  CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= \
  build-for-testing 2>&1 | tee "$work/ui-build.log"
app="$work/DerivedData/Build/Products/Debug-iphonesimulator/MeshGram.app"
codesign --verify --strict "$app"
codesign -dv --verbose=2 "$app" 2> "$work/simulator-signature.log"
grep -qx 'Signature=adhoc' "$work/simulator-signature.log"
device=$(xcrun simctl create "MeshGram-UI-${GITHUB_RUN_ID:?}" "$(cat "$work/type-id.txt")" "$(cat "$work/runtime-id.txt")")
[[ "$device" =~ ^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$ ]]
cleanup() {
  xcrun simctl shutdown "$device" >/dev/null 2>&1 || true
  xcrun simctl delete "$device" >/dev/null 2>&1 || true
}
trap cleanup EXIT
xcrun simctl boot "$device"
xcrun simctl bootstatus "$device" -b
test_status=0
if xcodebuild -project "$root/MeshGram.xcodeproj" -scheme MeshGramUI \
  -destination "platform=iOS Simulator,id=$device" -destination-timeout 60 \
  -derivedDataPath "$work/DerivedData" -resultBundlePath "$work/results.xcresult" \
  -parallel-testing-enabled NO ARCHS="$MESHGRAM_UI_ARCH" ONLY_ACTIVE_ARCH=YES \
  -test-timeouts-enabled YES -maximum-test-execution-time-allowance 180 \
  -default-test-execution-time-allowance 180 \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual \
  DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= \
  test-without-building 2>&1 | tee "$work/ui-test.log"; then
  test_status=0
else
  codes=("${PIPESTATUS[@]}")
  test_status=${codes[0]}
  if [[ "$test_status" == 0 ]]; then test_status=${codes[1]}; fi
fi
export_status=0
if [[ -d "$work/results.xcresult" ]]; then
  xcrun xcresulttool export attachments --path "$work/results.xcresult" --output-path "$work/raw-attachments" || export_status=$?
else
  export_status=1
fi
export MESHGRAM_UI_TEST_STATUS="$test_status" MESHGRAM_UI_EXPORT_STATUS="$export_status"
python3 - <<'PY'
import hashlib, json, os, re, shutil
from pathlib import Path
work = Path(os.environ['MESHGRAM_UI_WORK'])
raw = work/'raw-attachments'
images = []
for source in sorted(raw.rglob('*.png')) if raw.exists() else []:
    assert not source.is_symlink() and source.resolve().is_relative_to(raw.resolve())
    assert source.stat().st_size <= 12 * 1024**2 and len(images) < 6
    with source.open('rb') as stream:
        assert stream.read(8) == b'\x89PNG\r\n\x1a\n'
    target = work/'artifacts'/f'friendship-ui-{len(images)+1:02}.png'
    shutil.copyfile(source, target)
    images.append({'name':target.name, 'bytes':target.stat().st_size, 'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
code = int(os.environ['MESHGRAM_UI_TEST_STATUS'])
export_code = int(os.environ['MESHGRAM_UI_EXPORT_STATUS'])
log = (work/'ui-test.log').read_text(errors='replace')
totals = [(int(count), int(failures)) for count, failures in re.findall(r'Executed (\d+) tests?, with (\d+) failures?', log)]
executed = max((count for count, _ in totals), default=0)
failures = max((failures for _, failures in totals), default=None)
case_passed = bool(re.search(r'testRussianSetupCancelAndPersistenceOnFreshSimulator[^\n]*passed', log))
summary = dict(json.loads((work/'selection.json').read_text()), commit=os.environ['GITHUB_SHA'],
               exitCode=code, exportExitCode=export_code, uiTestCases=executed, uiFailures=failures, casePassed=case_passed,
               passed=code == 0 and export_code == 0 and bool(images) and executed == 1 and failures == 0 and case_passed,
               screenshots=images, ownerDevicesUsed=False, ownerCredentialsUsed=False, fullResultUploaded=False)
(work/'artifacts'/'ui-summary.json').write_text(json.dumps(summary, indent=2))
assert summary['passed'], 'UI run or screenshot export failed; not accepted'
PY
