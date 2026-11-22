package tn.supcom.examguard.api.copies;

import jakarta.enterprise.context.ApplicationScoped;
import tn.supcom.examguard.crypto.Nonces;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stockage de demonstration, borne et limite a la vie du deploiement. */
@ApplicationScoped
public class ExamCopyStore {
    private static final int MAX_COPIES = 4;

    private final Map<UUID, StoredCopy> copies = new ConcurrentHashMap<>();
    private final KeyPair signingKey = newSigningKey();

    public synchronized Optional<UUID> issue(String recipientId, byte[] pdf) throws GeneralSecurityException {
        if (copies.size() >= MAX_COPIES) {
            return Optional.empty();
        }

        UUID copyId = UUID.randomUUID();
        byte[] hash = sha256(pdf);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signingKey.getPrivate());
        signature.update(proof(copyId, hash));

        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        byte[] nonce = Nonces.randomGcmNonce();
        byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, copyId, key, nonce).doFinal(pdf);
        copies.put(copyId, new StoredCopy(recipientId, key, nonce, ciphertext, hash, signature.sign()));
        return Optional.of(copyId);
    }

    public Optional<byte[]> download(UUID copyId, String recipientId) throws GeneralSecurityException {
        StoredCopy copy = copies.get(copyId);
        if (copy == null || !copy.recipientId().equals(recipientId)) {
            return Optional.empty();
        }
        return Optional.of(gcm(Cipher.DECRYPT_MODE, copyId, copy.key(), copy.nonce()).doFinal(copy.ciphertext()));
    }

    public Optional<Verdict> verify(UUID copyId, byte[] candidate) throws GeneralSecurityException {
        StoredCopy copy = copies.get(copyId);
        if (copy == null) {
            return Optional.empty();
        }

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(signingKey.getPublic());
        verifier.update(proof(copyId, copy.hash()));
        if (!verifier.verify(copy.signature())) {
            return Optional.of(Verdict.INDETERMINATE);
        }
        boolean matches = MessageDigest.isEqual(copy.hash(), sha256(candidate));
        return Optional.of(matches ? Verdict.VALID : Verdict.HASH_MISMATCH);
    }

    private static byte[] proof(UUID copyId, byte[] hash) {
        // Champs ASCII fixes, tries selon JCS. Aucun texte utilisateur dans la preuve.
        String canonical = "{\"copyId\":\"" + copyId + "\",\"domain\":\"EG/FILE/1\",\"sha256\":\""
                + HexFormat.of().formatHex(hash) + "\"}";
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] document) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(document);
    }

    private static Cipher gcm(int mode, UUID copyId, SecretKey key, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(copyId.toString().getBytes(StandardCharsets.US_ASCII));
        return cipher;
    }

    private static KeyPair newSigningKey() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 indisponible", e);
        }
    }

    public enum Verdict {
        VALID, HASH_MISMATCH, INDETERMINATE
    }

    private record StoredCopy(String recipientId, SecretKey key, byte[] nonce,
                              byte[] ciphertext, byte[] hash, byte[] signature) {
    }
}
