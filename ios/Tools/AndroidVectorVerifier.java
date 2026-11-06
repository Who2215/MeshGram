import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// Independent JCE check using the algorithms and encodings in Android SecureCryptoEngine.
public final class AndroidVectorVerifier {
    static final Properties p = new Properties();
    static byte[] bytes(String key) { return Base64.getDecoder().decode(p.getProperty(key)); }
    static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        try (var reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) { p.load(reader); }
        var factory = KeyFactory.getInstance("EC");
        var signingKey = factory.generatePublic(new X509EncodedKeySpec(bytes("signingPublicKey")));
        var signature = Signature.getInstance("SHA256withECDSA");
        for (var prefix : new String[]{"hello", "message"}) {
            signature.initVerify(signingKey);
            signature.update(bytes(prefix + "Bytes"));
            require(signature.verify(bytes(prefix + "Signature")), prefix + " signature");
            var tampered = bytes(prefix + "Bytes"); tampered[0] ^= 1;
            signature.initVerify(signingKey); signature.update(tampered);
            require(!signature.verify(bytes(prefix + "Signature")), prefix + " tamper rejection");
        }
        var fingerprint = MessageDigest.getInstance("SHA-256").digest(
            (p.getProperty("encryptionPublicKey") + "|" + p.getProperty("signingPublicKey")).getBytes(StandardCharsets.UTF_8));
        require(HexFormat.of().formatHex(fingerprint).equals(p.getProperty("fingerprint")), "fingerprint");
        var receiver = factory.generatePrivate(new PKCS8EncodedKeySpec(bytes("receiverPKCS8")));
        var ephemeral = factory.generatePublic(new X509EncodedKeySpec(bytes("ephemeralPublicKey")));
        var agreement = KeyAgreement.getInstance("ECDH"); agreement.init(receiver); agreement.doPhase(ephemeral, true);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(bytes("salt"), "HmacSHA256"));
        var prk = mac.doFinal(agreement.generateSecret());
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update("mesh-e2e-v1".getBytes(StandardCharsets.UTF_8)); mac.update((byte) 1);
        var key = mac.doFinal();
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, bytes("nonce")));
        cipher.updateAAD(bytes("aad"));
        require(Arrays.equals(cipher.doFinal(bytes("ciphertext")), bytes("plaintext")), "plaintext");
        var changed = bytes("ciphertext"); changed[0] ^= 1;
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, bytes("nonce")));
        cipher.updateAAD(bytes("aad"));
        boolean rejected = false;
        try { cipher.doFinal(changed); } catch (javax.crypto.AEADBadTagException expected) { rejected = true; }
        require(rejected, "GCM tag rejection");
        System.out.println("JCE: DER signatures, tamper rejection, fingerprint, ECDH/HKDF/GCM passed.");
    }
}
