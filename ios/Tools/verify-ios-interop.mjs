import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync, statSync, mkdtempSync, writeFileSync, existsSync, unlinkSync, rmdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const known = JSON.parse(readFileSync(join(here, '../Tests/MeshGramCoreTests/Fixtures/android-v1.json'), 'utf8'));
const b64 = text => Buffer.from(text, 'utf8').toString('base64');

export function vectorProperties(vector) {
  assert.ok(vector.note === known.note, 'Expected a labelled public synthetic fixture');
  assert.ok(vector.receiverPKCS8 === known.receiverPKCS8, 'Only the already-public receiver key is allowed');
  assert.ok(vector.plaintext === known.plaintext, 'Only the already-public synthetic plaintext is allowed');
  const { hello: h, packet: p } = vector;
  assert.equal(h.type, 'HELLO_V1');
  assert.equal(h.profileVersion, 2);
  assert.equal(typeof h.discoverable, 'boolean');
  assert.equal(p.type, 'SECURE_MESSAGE_V1');
  assert.equal(p.targetNodeId, known.packet.targetNodeId);
  assert.equal(p.originNodeId, h.originNodeId);
  assert.equal(p.relayNodeId, p.originNodeId);
  assert.equal(p.hops, 0);
  assert.ok(Number.isSafeInteger(p.createdAtMs) && p.createdAtMs > 0);
  assert.ok(Number.isInteger(p.maxHops) && p.maxHops >= 1 && p.maxHops <= 24);
  assert.equal(p.senderEncryptionPublicKey, h.encryptionPublicKey);
  assert.equal(p.senderSigningPublicKey, h.signingPublicKey);
  assert.equal(p.senderFingerprint, h.fingerprint);
  assert.match(h.fingerprint, /^[0-9a-f]{64}$/);
  for (const value of [h.frameId, h.originNodeId, p.id, p.targetNodeId]) {
    assert.ok(typeof value === 'string' && value.length > 0 && Buffer.byteLength(value) <= 128 &&
      !/[|\x00-\x1f\x7f]/.test(value), 'Invalid canonical identifier');
  }
  // Raw property values must be canonical Base64: embedded newlines could inject Java properties.
  for (const value of [h.encryptionPublicKey, h.signingPublicKey, h.signature, p.signature,
    p.ephemeralPublicKey, p.nonce, p.ciphertext]) {
    assert.ok(typeof value === 'string' && value.length > 0 && value.length <= 65_536 &&
      Buffer.from(value, 'base64').toString('base64') === value, 'Invalid Base64 property');
  }
  assert.equal(Buffer.from(p.nonce, 'base64').length, 12);
  assert.equal(Buffer.from(p.ciphertext, 'base64').length, Buffer.from(known.plaintext, 'base64').length + 16);
  // Rebuild the Android canonical fields independently; do not trust bytes/salt/AAD supplied by Swift.
  const hello = [h.frameId, h.originNodeId, h.alias, h.encryptionPublicKey, h.signingPublicKey,
    h.fingerprint, h.createdAtMs, h.maxHops, h.avatarData ?? '', 'profile-v2', String(h.discoverable)].join('|');
  const message = [p.id, p.originNodeId, p.senderAlias, p.senderEncryptionPublicKey, p.senderSigningPublicKey,
    p.senderFingerprint, p.targetNodeId, p.createdAtMs, p.maxHops, p.ephemeralPublicKey, p.nonce, p.ciphertext].join('|');
  return { signingPublicKey: h.signingPublicKey, encryptionPublicKey: h.encryptionPublicKey,
    fingerprint: h.fingerprint, helloBytes: b64(hello), helloSignature: h.signature,
    messageBytes: b64(message), messageSignature: p.signature, receiverPKCS8: vector.receiverPKCS8,
    ephemeralPublicKey: p.ephemeralPublicKey, nonce: p.nonce, ciphertext: p.ciphertext,
    plaintext: vector.plaintext, salt: b64(`salt|${p.id}|${p.createdAtMs}|${p.targetNodeId}`),
    aad: b64(`aad|${p.id}|${p.originNodeId}|${p.targetNodeId}|${p.createdAtMs}|${p.maxHops}`) };
}

export function verifyInteropFixture(path) {
  assert.ok(statSync(path).size <= 128 * 1024, 'Oversized synthetic fixture');
  const properties = vectorProperties(JSON.parse(readFileSync(path, 'utf8')));
  const directory = mkdtempSync(join(tmpdir(), 'meshgram-ios-jce-'));
  const input = join(directory, 'synthetic.properties');
  try {
    writeFileSync(input, Object.entries(properties).map(([key, value]) => `${key}=${value}`).join('\n'),
      { flag: 'wx', mode: 0o600 });
    const java = process.env.JAVA_HOME
      ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
    const result = spawnSync(java, [join(here, 'AndroidVectorVerifier.java'), input],
      { encoding: 'utf8', timeout: 30_000, maxBuffer: 256 * 1024, windowsHide: true });
    assert.equal(result.status, 0, `Independent JCE failed: ${result.error?.message ?? result.stderr ?? ''}`);
    assert.match(result.stdout, /JCE:.*GCM passed/);
    return result.stdout.trim();
  } finally {
    if (existsSync(input)) unlinkSync(input);
    rmdirSync(directory);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  assert.equal(process.argv.length, 3, 'Pass exactly one synthetic JSON fixture');
  console.log(verifyInteropFixture(process.argv[2]));
}
