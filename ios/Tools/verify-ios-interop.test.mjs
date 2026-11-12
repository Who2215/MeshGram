import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, unlinkSync, rmdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { vectorProperties, verifyInteropFixture } from './verify-ios-interop.mjs';

const fixturePath = fileURLToPath(new URL('../Tests/MeshGramCoreTests/Fixtures/android-v1.json', import.meta.url));
const fixture = JSON.parse(readFileSync(fixturePath, 'utf8'));
const copy = () => structuredClone(fixture);

test('canonical bytes, salt and AAD are rebuilt, not copied from input', () => {
  const v = copy(); v.helloBytes = 'wrong'; v.messageBytes = 'wrong'; v.salt = 'wrong'; v.aad = 'wrong';
  const p = vectorProperties(v);
  assert.equal(p.helloBytes, fixture.helloBytes);
  assert.equal(p.messageBytes, fixture.messageBytes);
  assert.equal(Buffer.from(p.salt, 'base64').toString(), `salt|${v.packet.id}|${v.packet.createdAtMs}|${v.packet.targetNodeId}`);
});

test('unlabelled, private-key or real-message inputs are rejected', () => {
  for (const field of ['note', 'receiverPKCS8', 'plaintext']) {
    const v = copy(); v[field] = 'not the public synthetic fixture';
    assert.throws(() => vectorProperties(v));
  }
});

test('sender and recipient bindings cannot be substituted', () => {
  for (const field of ['originNodeId', 'targetNodeId', 'senderFingerprint', 'senderEncryptionPublicKey', 'senderSigningPublicKey']) {
    const v = copy(); v.packet[field] = 'changed';
    assert.throws(() => vectorProperties(v));
  }
});

test('invalid wire versions, counters and nonce lengths are rejected', () => {
  for (const [field, value] of [['type', 'OTHER'], ['hops', 1], ['maxHops', 0], ['maxHops', 25],
    ['createdAtMs', 0], ['createdAtMs', Number.MAX_SAFE_INTEGER + 1], ['nonce', 'AA==']]) {
    const v = copy(); v.packet[field] = value;
    assert.throws(() => vectorProperties(v));
  }
  const v = copy(); v.hello.profileVersion = 1;
  assert.throws(() => vectorProperties(v));
});

test('newlines cannot inject Java properties through raw Base64 values', () => {
  for (const field of ['signature', 'ephemeralPublicKey', 'nonce', 'ciphertext']) {
    const v = copy(); v.packet[field] += '\nmessageBytes=forged';
    assert.throws(() => vectorProperties(v), /Base64/);
  }
  const v = copy(); v.packet.id = 'id\nplaintext=forged';
  assert.throws(() => vectorProperties(v), /identifier/);
});

test('oversized fixture is rejected before JSON decoding or JCE', () => {
  const dir = mkdtempSync(join(tmpdir(), 'meshgram-interop-test-'));
  const file = join(dir, 'large.json');
  try {
    writeFileSync(file, 'x'.repeat(128 * 1024 + 1));
    assert.throws(() => verifyInteropFixture(file), /Oversized/);
  } finally { unlinkSync(file); rmdirSync(dir); }
});

test('independent Java verifier accepts the existing public baseline', { timeout: 40_000 }, () => {
  assert.match(verifyInteropFixture(fixturePath), /JCE:.*GCM passed/);
});
