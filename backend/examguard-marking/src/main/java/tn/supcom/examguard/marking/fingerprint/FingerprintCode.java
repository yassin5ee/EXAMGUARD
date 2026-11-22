package tn.supcom.examguard.marking.fingerprint;

import javax.crypto.KDF;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.HKDFParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.UUID;

/** Mot de code de l'annexe A.1, indépendant de la manière de l'insérer dans le PDF. */
public final class FingerprintCode {
    public static final int BIT_LENGTH = Long.SIZE;

    private static final String HMAC = "HmacSHA256";
    private static final byte[] SALT_LABEL = "ExamGuard/HKDF/v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DOMAIN = "EG/fp/v1".getBytes(StandardCharsets.UTF_8);
    private static final int KEY_BYTES = 32;
    private static final int UUID_BYTES = 2 * Long.BYTES;
    private static final int IDENTIFIER_BYTES = 6;
    private static final int FIELD_HEADER_BYTES = Byte.BYTES + Integer.BYTES;
    private static final byte TEXT_FIELD = 1;
    private static final byte UUID_FIELD = 2;
    private static final int CRC_MASK = 0xFFFF;
    private static final int CRC_POLYNOMIAL = 0x1021;

    private FingerprintCode() {
    }

    /**
     * Dérive un mot de code de 64 bits, à lire du bit de poids fort au bit de poids faible.
     * La clé maîtresse est une clé de marquage aléatoire de 256 bits.
     */
    public static long derive(SecretKey masterKey, UUID examId, UUID distributionId)
            throws GeneralSecurityException {
        byte[] salt = MessageDigest.getInstance("SHA-256").digest(SALT_LABEL);
        var parameters = HKDFParameterSpec.ofExtract()
                .addIKM(masterKey)
                .addSalt(salt)
                .thenExpand(examContext(examId), KEY_BYTES);
        SecretKey examKey = KDF.getInstance("HKDF-SHA256").deriveKey(HMAC, parameters);

        Mac hmac = Mac.getInstance(HMAC);
        hmac.init(examKey);
        byte[] digest = hmac.doFinal(putUuid(ByteBuffer.allocate(UUID_BYTES), distributionId).array());
        return ByteBuffer.allocate(Long.BYTES)
                .put(digest, 0, IDENTIFIER_BYTES)
                .putShort((short) checksum(digest))
                .getLong(0);
    }

    /** Vérifie uniquement le CRC. Une correspondance doit encore être confirmée par HMAC. */
    public static boolean isValid(long code) {
        ByteBuffer payload = ByteBuffer.allocate(Long.BYTES).putLong(code);
        int expected = Short.toUnsignedInt(payload.getShort(IDENTIFIER_BYTES));
        return checksum(payload.array()) == expected;
    }

    // Contexte v1 : type (1 octet), longueur (uint32 big-endian), valeur, pour chaque champ.
    // Type 1 : texte UTF-8. Type 2 : UUID binaire, 128 bits de poids fort à poids faible.
    private static byte[] examContext(UUID examId) {
        ByteBuffer context = ByteBuffer.allocate(2 * FIELD_HEADER_BYTES + DOMAIN.length + UUID_BYTES);
        context.put(TEXT_FIELD).putInt(DOMAIN.length).put(DOMAIN);
        context.put(UUID_FIELD).putInt(UUID_BYTES);
        return putUuid(context, examId).array();
    }

    private static ByteBuffer putUuid(ByteBuffer buffer, UUID id) {
        return buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
    }

    // CRC-16/CCITT-FALSE : init 0xFFFF, aucune réflexion, XOR final 0x0000.
    private static int checksum(byte[] data) {
        int crc = CRC_MASK;
        for (int i = 0; i < IDENTIFIER_BYTES; i++) {
            crc ^= Byte.toUnsignedInt(data[i]) << Byte.SIZE;
            for (int bit = 0; bit < Byte.SIZE; bit++) {
                boolean highBit = (crc & 0x8000) != 0;
                crc = (crc << 1) & CRC_MASK;
                if (highBit) {
                    crc ^= CRC_POLYNOMIAL;
                }
            }
        }
        return crc;
    }
}
