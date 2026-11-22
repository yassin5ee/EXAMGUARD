package tn.supcom.examguard.marking.fingerprint;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerprintCodeTest {
    private static final UUID EXAM_ID = UUID.fromString("01234567-89ab-cdef-fedc-ba9876543210");
    private static final UUID DISTRIBUTION_ID = UUID.fromString("fedcba98-7654-3210-0123-456789abcdef");

    @Test
    void derivesStableCodesScopedToTheExamAndDistribution() throws Exception {
        // Vecteurs calculés indépendamment avec hmac et binascii.crc_hqx de Python.
        SecretKey testKey = knownTestKey();
        assertEquals(0xbce4ee0314179ed4L, FingerprintCode.derive(testKey, EXAM_ID, DISTRIBUTION_ID));
        UUID anotherDistribution = UUID.fromString("fedcba98-7654-3210-0123-456789abcdee");
        assertEquals(0xa3bcbe730e1db68dL, FingerprintCode.derive(testKey, EXAM_ID, anotherDistribution));
        UUID anotherExam = UUID.fromString("01234567-89ab-cdef-fedc-ba9876543211");
        assertEquals(0x21ec3c1a843849e1L, FingerprintCode.derive(testKey, anotherExam, DISTRIBUTION_ID));
    }

    @Test
    void catchesOneBitCorruptionAnywhereInTheCode() {
        long knownCode = 0xbce4ee0314179ed4L;
        assertTrue(FingerprintCode.isValid(knownCode));
        for (int bit = 0; bit < FingerprintCode.BIT_LENGTH; bit++) {
            assertFalse(FingerprintCode.isValid(knownCode ^ (1L << bit)), "bit altéré : " + bit);
        }
    }

    @Test
    void sampleOfOneThousandDistributionsProducesDistinctIdentifiers() throws Exception {
        SecretKey testKey = knownTestKey();
        var identifiers = HashSet.<Long>newHashSet(1_000);
        for (int i = 0; i < 1_000; i++) {
            UUID distribution = new UUID(DISTRIBUTION_ID.getMostSignificantBits(), i);
            long code = FingerprintCode.derive(testKey, EXAM_ID, distribution);
            assertTrue(identifiers.add(code >>> Short.SIZE), "identifiant dupliqué : " + distribution);
        }
    }

    private static SecretKey knownTestKey() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
