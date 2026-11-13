import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync, writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createPublicKey, verify, createHash } from 'node:crypto';

const root = fileURLToPath(new URL('../', import.meta.url));
const read = path => readFileSync(join(root, path), 'utf8');
const fixture = JSON.parse(read('Tests/MeshGramCoreTests/Fixtures/android-v1.json'));
const { hello: h, packet: p } = fixture;
const from64 = value => Buffer.from(value, 'base64');
const helloBytes = Buffer.from([h.frameId, h.originNodeId, h.alias, h.encryptionPublicKey, h.signingPublicKey,
  h.fingerprint, h.createdAtMs, h.maxHops, h.avatarData, 'profile-v2', String(h.discoverable)].join('|'));
const messageBytes = Buffer.from([p.id, p.originNodeId, p.senderAlias, p.senderEncryptionPublicKey,
  p.senderSigningPublicKey, p.senderFingerprint, p.targetNodeId, p.createdAtMs, p.maxHops,
  p.ephemeralPublicKey, p.nonce, p.ciphertext].join('|'));

test('protocol fixture binds the canonical Android fields, DER signatures and profile consent', () => {
  assert.deepEqual(helloBytes, from64(fixture.helloBytes));
  assert.deepEqual(messageBytes, from64(fixture.messageBytes));
  const key = createPublicKey({ key: from64(h.signingPublicKey), type: 'spki', format: 'der' });
  assert.equal(verify('sha256', helloBytes, key, from64(h.signature)), true);
  assert.equal(verify('sha256', messageBytes, key, from64(p.signature)), true);
  assert.equal(verify('sha256', Buffer.from(helloBytes.toString().replace(/true$/, 'false')), key, from64(h.signature)), false);
  assert.equal(createHash('sha256').update(`${h.encryptionPublicKey}|${h.signingPublicKey}`).digest('hex'), h.fingerprint);
});

test('independent Java JCE verifies signatures and decrypts the public fixture', () => {
  const salt = Buffer.from(`salt|${p.id}|${p.createdAtMs}|${p.targetNodeId}`);
  const aad = Buffer.from(`aad|${p.id}|${p.originNodeId}|${p.targetNodeId}|${p.createdAtMs}|${p.maxHops}`);
  const properties = { signingPublicKey: h.signingPublicKey, encryptionPublicKey: h.encryptionPublicKey,
    fingerprint: h.fingerprint, helloBytes: helloBytes.toString('base64'), helloSignature: h.signature,
    messageBytes: messageBytes.toString('base64'), messageSignature: p.signature,
    receiverPKCS8: fixture.receiverPKCS8, ephemeralPublicKey: p.ephemeralPublicKey,
    nonce: p.nonce, ciphertext: p.ciphertext, plaintext: fixture.plaintext,
    salt: salt.toString('base64'), aad: aad.toString('base64') };
  const folder = mkdtempSync(join(tmpdir(), 'meshgram-ios-vector-'));
  const path = join(folder, 'vector.properties');
  writeFileSync(path, Object.entries(properties).map(([key, value]) => `${key}=${value}`).join('\n'));
  const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const result = spawnSync(java, [join(root, 'Tools/AndroidVectorVerifier.java'), path], { encoding: 'utf8', timeout: 30_000 });
  assert.equal(result.status, 0, (result.stderr ?? '') + (result.stdout ?? ''));
  assert.match(result.stdout, /GCM passed/);
});

test('iOS constants match Android, not guessed BLE identifiers or hop ceilings', () => {
  const android = readFileSync(resolve(root, '../app/src/main/java/com/meshchat/app/mesh/BleMeshManager.kt'), 'utf8');
  const wire = read('Sources/MeshGramCore/WirePackets.swift');
  for (const uuid of ['88a7588a-8869-4453-91f8-f53db3564f06', '4db27553-b5d3-4626-95f6-b2e67a4b3597', '0ecae0e1-53f6-4df0-b0e8-01e1478196ab']) {
    assert.ok(wire.includes(uuid) && android.includes(uuid));
  }
  const helloMax = Number(android.match(/HELLO_MAX_HOPS = (\d+)/)[1]);
  const messageMax = Number(android.match(/MESSAGE_MAX_HOPS = (\d+)/)[1]);
  assert.ok(wire.includes(`helloMaxHops = ${helloMax}`));
  assert.ok(wire.includes(`messageMaxHops = ${messageMax}`));
  assert.match(read('Sources/MeshGramCore/PacketCrypto.swift'), /MeshWire\.messageMaxHops/);
});

test('Xcode project regeneration is deterministic and all object references resolve', () => {
  const before = read('MeshGram.xcodeproj/project.pbxproj');
  const uiScheme = read('MeshGram.xcodeproj/xcshareddata/xcschemes/MeshGramUI.xcscheme');
  const result = spawnSync(process.execPath, [join(root, 'Tools/generate-project.mjs')], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(read('MeshGram.xcodeproj/project.pbxproj'), before);
  assert.equal(read('MeshGram.xcodeproj/xcshareddata/xcschemes/MeshGramUI.xcscheme'), uiScheme);
  const ids = [...before.matchAll(/^([A-F0-9]{24}) = \{/gm)].map(match => match[1]);
  assert.equal(ids.length, new Set(ids).size);
  for (const [reference] of before.matchAll(/\b[A-F0-9]{24}\b/g)) assert.ok(ids.includes(reference));
  for (const file of ['MeshGramApp.swift', 'IdentityStore.swift', 'NearbyScanner.swift', 'ContactKeychainStore.swift', 'FriendshipPane.swift']) {
    assert.ok(before.includes(file));
    assert.ok(read(`App/${file}`).length > 50);
  }
  assert.match(read('MeshGram.xcodeproj/xcshareddata/xcschemes/MeshGram.xcscheme'), /BlueprintName="MeshGram"/);
});

test('prototype does not advertise implemented chat, background execution or plaintext key storage', () => {
  assert.match(read('App/MeshGramApp.swift'), /пока не подключены/);
  assert.doesNotMatch(read('App/Info.plist'), /UIBackgroundModes|NSAppTransportSecurity/);
  const storage = read('App/IdentityStore.swift');
  assert.match(storage, /kSecAttrAccessibleWhenUnlockedThisDeviceOnly/);
  assert.match(storage, /guard status == errSecItemNotFound/);
  assert.doesNotMatch(storage, /UserDefaults|write\(to:/);
  const contacts = read('App/ContactKeychainStore.swift');
  assert.match(contacts, /com\.meshgram\.ios\.contacts\.pins-v1/);
  assert.match(contacts, /kSecAttrAccessibleWhenUnlockedThisDeviceOnly/);
  assert.match(contacts, /errSecDuplicateItem/);
  assert.doesNotMatch(contacts, /SecItemDelete|SecItemUpdate|UserDefaults|write\(to:/);
});

test('Russian friendship setup is identity-bound, explicit and never a reload fallback', () => {
  const source = read('App/FriendshipPane.swift');
  assert.match(source, /@MainActor/);
  assert.match(source, /catch FriendStoreError\.notInitialized/);
  const reload = source.slice(source.indexOf('    func reload()'), source.indexOf('    func initializeNew()'));
  assert.doesNotMatch(reload, /createDirectory|initializeNew\(/);
  assert.match(source, /guard identityIsCurrent\(\) else/);
  assert.match(source, /guard phase == \.needsInitialization/);
  assert.ok(source.indexOf('createDirectory') < source.indexOf('repository.initializeNew()'));
  assert.ok(source.indexOf('attributes[.type]') < source.indexOf('repository.initializeNew()'));
  assert.match(source, /resolvingSymlinksInPath/);
  assert.match(source, /setAttributes\(\[\.posixPermissions: 0o700\], ofItemAtPath: parent\.path\)/);
  assert.ok(source.indexOf('guard permissions?.intValue == 0o700') < source.indexOf('repository.initializeNew()'));
  for (const text of ['Дружба', 'Создать защищённый список друзей?', 'Создать список', 'Отмена', 'Повторить чтение списка']) assert.ok(source.includes(text));
  assert.match(source, /Button\("Создать список"\) \{ model\.initializeNew\(\) \}/);
  assert.match(read('App/NearbyScanner.swift'), /identityIsCurrent: \{ \[weak self\] in self\?\.publicIdentity == identity \}/);
  assert.doesNotMatch(source, /SecItemDelete|SecItemUpdate|loadOrCreate|removeItem|UserDefaults/);
});

test('shared friendship model and secure callbacks remain identity/link bound without auto-init or send', () => {
  const scanner = read('App/NearbyScanner.swift'), model = read('App/FriendshipPane.swift');
  assert.match(model, /@ObservedObject private var model: FriendshipModel/);
  assert.doesNotMatch(model, /@StateObject/);
  assert.match(scanner, /@MainActor private var cachedFriendship/);
  assert.match(scanner, /if let cachedFriendship, cachedFriendship\.isBound\(to: identity\) \{ return cachedFriendship \}/);
  assert.match(scanner, /defer \{ Task \{ @MainActor \[weak self\] in self\?\.discardStaleFriendship\(\)/);
  assert.match(scanner, /pendingSecureReceives\.count < 8/);
  assert.match(scanner, /Task \{ @MainActor \[weak self, weak link\]/);
  const receive = scanner.slice(scanner.indexOf('    private func receiveFriendshipPayload'), scanner.indexOf('    private func received('));
  for (const text of ['current(link)', 'contactsAvailable', 'confirmationGate.candidate', 'book.match', 'model.phase == .ready', 'packet.originNodeId == candidate.nodeId', 'packet.senderFingerprint == candidate.fingerprint']) assert.ok(receive.includes(text));
  assert.doesNotMatch(receive, /FriendshipModel\(|friendship\(for:|writeValue|start\(\)|initializeNew/);
  const delivery = model.slice(model.indexOf('    func receiveFriendship'), model.indexOf('    func initializeNew'));
  assert.match(delivery, /guard phase == \.ready/);
  assert.match(delivery, /nearby: false/);
  assert.doesNotMatch(delivery, /createDirectory|initializeNew\(|load\(|writeValue/);
  const dispatch = scanner.slice(scanner.indexOf('    private func received('));
  assert.ok(dispatch.indexOf('enqueueFriendship') < dispatch.indexOf('clearConfirmation(link, detail: "Проверяем новый пакет'));
  assert.match(read('UITests/FriendshipUITests.swift'), /stop\.tap\(\)/);
});

test('UI smoke uses only a new CI simulator and exports bounded screenshots, never owner data', () => {
  const project = read('MeshGram.xcodeproj/project.pbxproj');
  const scheme = read('MeshGram.xcodeproj/xcshareddata/xcschemes/MeshGramUI.xcscheme');
  const script = read('Tools/check-simulator-ui.sh');
  const ui = read('UITests/FriendshipUITests.swift');
  assert.match(project, /com\.apple\.product-type\.bundle\.ui-testing/);
  assert.match(project, /TEST_TARGET_NAME = MeshGram/);
  assert.match(scheme, /TestableReference skipped="NO"/);
  assert.match(scheme, /MeshGramUITests\.xctest/);
  for (const text of ['GITHUB_ACTIONS', 'simctl create', 'simctl delete "$device"', 'xcresulttool export attachments', 'uiTestCases']) assert.ok(script.includes(text));
  assert.doesNotMatch(script, /simctl (erase|delete) all|downloadRuntime|devicectl|idevice|\badb\b/);
  assert.ok(script.includes('ARCHS="$MESHGRAM_UI_ARCH" ONLY_ACTIVE_ARCH=YES'));
  assert.ok(script.indexOf('build-for-testing') < script.indexOf('simctl create'));
  assert.ok(script.indexOf('test-without-building') > script.indexOf('simctl bootstatus'));
  assert.match(script, /-test-timeouts-enabled YES -maximum-test-execution-time-allowance 300/);
  assert.match(script, /-default-test-execution-time-allowance 300/);
  assert.equal((script.match(/CODE_SIGNING_ALLOWED=YES/g) ?? []).length, 2);
  assert.equal((script.match(/CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual/g) ?? []).length, 2);
  assert.match(script, /codesign --verify --strict/);
  assert.match(script, /Signature=adhoc/);
  assert.doesNotMatch(script, /CODE_SIGNING_ALLOWED=NO|-allowProvisioningUpdates/);
  assert.ok(script.includes('executed == 1 and failures == 0 and case_passed'));
  assert.match(ui, /alert\.buttons\["Отмена"\]\.tap\(\)/);
  assert.match(ui, /alert\.buttons\["Создать список"\]\.tap\(\)/);
  assert.match(ui, /app\.terminate\(\); app\.launch\(\)/);
  assert.doesNotMatch(ui, /SecItem|privateKey|UserDefaults|removeItem/);
  const workflow = readFileSync(resolve(root, '..', '.github/workflows/ios-cloud-check.yml'), 'utf8');
  const [native, isolated] = workflow.split('  simulator-ui:');
  assert.ok(native.includes('meshgram-ios-synthetic-vector-'));
  assert.match(isolated, /needs: native-check/);
  assert.match(native, /timeout-minutes: 15/);
  assert.match(isolated, /timeout-minutes: 35/);
  assert.ok(isolated.includes('git sparse-checkout set ios .github/workflows app/src/main/java/com/meshchat/app/mesh'));
  assert.doesNotMatch(isolated, /meshgram-ios-synthetic-vector|meshgram-ios-device/);
  assert.match(isolated, /meshgram-ios-ui\/artifacts\//);
});
