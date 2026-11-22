package tn.supcom.examguard.api.copies;

import jakarta.enterprise.context.ApplicationScoped;
import tn.supcom.examguard.crypto.Nonces;
import tn.supcom.examguard.marking.fingerprint.FingerprintCode;
import tn.supcom.examguard.marking.fingerprint.PdfFingerprint;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
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
    private final SecretKey markingKey = newMarkingKey();

    public synchronized Optional<UUID> issue(String recipientId, byte[] pdf)
            throws GeneralSecurityException, PdfFingerprint.PdfNotMarkableException {
        if (copies.size() >= MAX_COPIES) {
            return Optional.empty();
        }

        String originalHash = HexFormat.of().formatHex(sha256(pdf));
        SourceExam exam = copies.values().stream().map(StoredCopy::exam)
                .filter(source -> source.hash().equals(originalHash)).findFirst().orElse(null);
        UUID examId = exam == null ? UUID.randomUUID() : exam.id();
        UUID copyId = UUID.randomUUID();
        long code = FingerprintCode.derive(markingKey, examId, copyId);
        byte[] marked = PdfFingerprint.embed(pdf, code);
        byte[] hash = sha256(marked);
        if (exam == null) {
            exam = new SourceExam(examId, originalHash, encrypt(examId, pdf));
        }

        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signingKey.getPrivate());
        signature.update(proof(copyId, exam, hash));

        copies.put(copyId, new StoredCopy(recipientId, exam, encrypt(copyId, marked), hash, signature.sign()));
        return Optional.of(copyId);
    }

    public Optional<byte[]> download(UUID copyId, String recipientId) throws GeneralSecurityException {
        StoredCopy copy = copies.get(copyId);
        if (copy == null || !copy.recipientId().equals(recipientId)) {
            return Optional.empty();
        }
        return Optional.of(decrypt(copyId, copy.document()));
    }

    public Optional<Verdict> verify(UUID copyId, byte[] candidate) throws GeneralSecurityException {
        StoredCopy copy = copies.get(copyId);
        if (copy == null) {
            return Optional.empty();
        }

        if (!verifyProof(copyId, copy)) {
            return Optional.of(Verdict.INDETERMINATE);
        }
        boolean matches = MessageDigest.isEqual(copy.hash(), sha256(candidate));
        return Optional.of(matches ? Verdict.VALID : Verdict.HASH_MISMATCH);
    }

    public Optional<Identification> identify(byte[] candidate) throws GeneralSecurityException {
        Map<UUID, StoredCopy> registered = Map.copyOf(copies);
        byte[] hash = sha256(candidate);
        List<UUID> exact = registered.entrySet().stream()
                .filter(entry -> MessageDigest.isEqual(entry.getValue().hash(), hash))
                .map(Map.Entry::getKey).toList();
        if (!exact.isEmpty()) {
            UUID copyId = exact.getFirst();
            return exact.size() == 1 && verifyProof(copyId, registered.get(copyId))
                    ? Optional.of(new Identification(copyId, Evidence.EXACT_MATCH)) : Optional.empty();
        }

        Map<SourceExam, List<UUID>> distributions = new HashMap<>();
        registered.forEach((id, copy) -> distributions.computeIfAbsent(copy.exam(), ignored -> new ArrayList<>())
                .add(id));
        UUID found = null;
        for (var exam : distributions.entrySet()) {
            SourceExam source = exam.getKey();
            byte[] original = decrypt(source.id(), source.original());
            Optional<PdfFingerprint.Detection> detection = PdfFingerprint.detect(original, candidate);
            if (detection.isEmpty()) {
                continue;
            }
            for (UUID copyId : exam.getValue()) {
                if (FingerprintCode.derive(markingKey, source.id(), copyId) == detection.get().code()) {
                    if (found != null || !verifyProof(copyId, registered.get(copyId))) {
                        return Optional.empty();
                    }
                    found = copyId;
                }
            }
        }
        return found == null ? Optional.empty() : Optional.of(new Identification(found, Evidence.FINGERPRINT_HINT));
    }

    private boolean verifyProof(UUID copyId, StoredCopy copy) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(signingKey.getPublic());
        verifier.update(proof(copyId, copy.exam(), copy.hash()));
        return verifier.verify(copy.signature());
    }

    private static byte[] proof(UUID copyId, SourceExam exam, byte[] hash) {
        // Champs ASCII fixes, tries selon JCS. Aucun texte utilisateur dans la preuve.
        String canonical = "{\"copyId\":\"" + copyId + "\",\"domain\":\"EG/FILE/1\",\"examId\":\"" + exam.id()
                + "\",\"originalSha256\":\"" + exam.hash() + "\",\"sha256\":\"" + HexFormat.of().formatHex(hash) + "\"}";
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] document) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(document);
    }

    private static EncryptedPdf encrypt(UUID id, byte[] pdf) throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        byte[] nonce = Nonces.randomGcmNonce();
        byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, id, key, nonce).doFinal(pdf);
        return new EncryptedPdf(key, nonce, ciphertext);
    }

    private static byte[] decrypt(UUID id, EncryptedPdf pdf) throws GeneralSecurityException {
        return gcm(Cipher.DECRYPT_MODE, id, pdf.key(), pdf.nonce()).doFinal(pdf.ciphertext());
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

    private static SecretKey newMarkingKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("HmacSHA256");
            generator.init(256);
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 indisponible", e);
        }
    }

    public enum Verdict {
        VALID, HASH_MISMATCH, INDETERMINATE
    }

    public enum Evidence {
        EXACT_MATCH, FINGERPRINT_HINT
    }

    public record Identification(UUID copyId, Evidence evidence) {
    }

    private record EncryptedPdf(SecretKey key, byte[] nonce, byte[] ciphertext) {
    }

    private record SourceExam(UUID id, String hash, EncryptedPdf original) {
    }

    private record StoredCopy(String recipientId, SourceExam exam, EncryptedPdf document, byte[] hash, byte[] signature) {
    }
}
