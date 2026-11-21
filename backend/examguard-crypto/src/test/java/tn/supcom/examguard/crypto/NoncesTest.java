package tn.supcom.examguard.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoncesTest {

    @Test
    @DisplayName("un nonce GCM fait exactement 96 bits")
    void gcmNonceIs96Bits() {
        assertEquals(12, Nonces.randomGcmNonce().length);
    }

    /**
     * Unicite des nonces sous une meme cle : 100 000 tirages, aucune collision.
     * Verifie ES-CRY-02 avec une marge d'un ordre de grandeur sur le seuil declare.
     */
    @Test
    @DisplayName("100 000 nonces consecutifs sont tous distincts (ES-CRY-02)")
    void oneHundredThousandNoncesAreAllDistinct() {
        final int draws = 100_000;
        HexFormat hex = HexFormat.of();
        Set<String> seen = java.util.HashSet.newHashSet(draws);

        for (int i = 0; i < draws; i++) {
            String nonce = hex.formatHex(Nonces.randomGcmNonce());
            assertTrue(seen.add(nonce),
                    "collision de nonce au tirage " + i + " : " + nonce
                            + " -- toute reutilisation sous la meme cle casse GCM");
        }

        assertEquals(draws, seen.size());
    }
}
