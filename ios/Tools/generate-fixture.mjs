import { generateKeyPairSync, createHash, sign, diffieHellman, hkdfSync, createCipheriv, randomBytes } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Throwaway TEST keys only. Never run this against a phone identity or production key.
const pair = () => generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const b64key = key => key.export({ type: 'spki', format: 'der' }).toString('base64');
const enc = pair(), signing = pair(), receiver = pair(), ephemeral = pair();
const encKey = b64key(enc.publicKey), signingKey = b64key(signing.publicKey);
const fingerprint = createHash('sha256').update(`${encKey}|${signingKey}`).digest('hex');
const hello = { type: 'HELLO_V1', frameId: '11111111-1111-4111-8111-111111111111', originNodeId: 'ios-test-sender',
  relayNodeId: 'ios-test-sender', hops: 0, maxHops: 4, createdAtMs: 1793980800000, alias: 'Тестовый узел',
  encryptionPublicKey: encKey, signingPublicKey: signingKey, fingerprint, avatarData: '', profileVersion: 2, discoverable: true };
const helloBytes = Buffer.from([hello.frameId, hello.originNodeId, hello.alias, encKey, signingKey, fingerprint,
  hello.createdAtMs, hello.maxHops, hello.avatarData, 'profile-v2', 'true'].join('|'));
hello.signature = sign('sha256', helloBytes, signing.privateKey).toString('base64');
const packet = { type: 'SECURE_MESSAGE_V1', id: '22222222-2222-4222-8222-222222222222', originNodeId: 'ios-test-sender',
  senderAlias: 'Node-ios-', senderEncryptionPublicKey: encKey, senderSigningPublicKey: signingKey, senderFingerprint: fingerprint,
  targetNodeId: 'ios-test-receiver', relayNodeId: 'ios-test-sender', hops: 0, maxHops: 4, createdAtMs: hello.createdAtMs,
  ephemeralPublicKey: b64key(ephemeral.publicKey), nonce: randomBytes(12).toString('base64') };
const plaintext = Buffer.from(JSON.stringify({ type: 'CHAT_PAYLOAD_V1', chatId: 'dm:test', chatType: 'direct', payloadKind: 'text', text: 'Привет с другого клиента' }));
const salt = Buffer.from(`salt|${packet.id}|${packet.createdAtMs}|${packet.targetNodeId}`);
const aad = Buffer.from(`aad|${packet.id}|${packet.originNodeId}|${packet.targetNodeId}|${packet.createdAtMs}|${packet.maxHops}`);
const key = Buffer.from(hkdfSync('sha256', diffieHellman({ privateKey: ephemeral.privateKey, publicKey: receiver.publicKey }), salt, Buffer.from('mesh-e2e-v1'), 32));
const cipher = createCipheriv('aes-256-gcm', key, Buffer.from(packet.nonce, 'base64'));
cipher.setAAD(aad);
packet.ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final(), cipher.getAuthTag()]).toString('base64');
const messageBytes = Buffer.from([packet.id, packet.originNodeId, packet.senderAlias, encKey, signingKey, fingerprint,
  packet.targetNodeId, packet.createdAtMs, packet.maxHops, packet.ephemeralPublicKey, packet.nonce, packet.ciphertext].join('|'));
packet.signature = sign('sha256', messageBytes, signing.privateKey).toString('base64');
const privateJWK = receiver.privateKey.export({ format: 'jwk' });
const vector = { note: 'PUBLIC TEST FIXTURE: receiver key is intentionally public, never use for real messages.',
  hello, packet, privateScalar: Buffer.from(privateJWK.d, 'base64url').toString('base64'),
  receiverPKCS8: receiver.privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64'),
  plaintext: plaintext.toString('base64'), helloBytes: helloBytes.toString('base64'), messageBytes: messageBytes.toString('base64') };
const folder = fileURLToPath(new URL('../Tests/MeshGramCoreTests/Fixtures/', import.meta.url));
mkdirSync(folder, { recursive: true });
writeFileSync(new URL('../Tests/MeshGramCoreTests/Fixtures/android-v1.json', import.meta.url), JSON.stringify(vector, null, 2) + '\n');
process.stdout.write('Public protocol test fixture generated; no production key material used.\n');
