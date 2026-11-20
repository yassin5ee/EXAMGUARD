package tn.supcom.examguard.crypto;

import java.security.SecureRandom;

/**
 * Generation des nonces AES-GCM.
 *
 * <p>96 bits, tires de {@link SecureRandom} : c'est la longueur recommandee par le
 * NIST SP 800-38D, la seule pour laquelle GCM n'applique pas de derivation
 * supplementaire au vecteur d'initialisation.
 *
 * <p>Un nonce ne doit jamais etre reutilise sous la meme cle : sous GCM, une seule
 * repetition revele le XOR des deux textes clairs et compromet la cle
 * d'authentification. L'unicite est donc une propriete testee, pas supposee
 * (ES-CRY-02), et l'unicite est verifiee sur 100 000 tirages consecutifs.
 */
public final class Nonces {

    /** Longueur d'un nonce GCM, en octets (96 bits). */
    public static final int GCM_NONCE_LENGTH = 12;

    /**
     * Une seule instance : {@code SecureRandom} est sur, reensemence seul,
     * et sa construction est couteuse.
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private Nonces() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * @return un nonce de 96 bits fraichement tire ; l'appelant en devient proprietaire.
     */
    public static byte[] randomGcmNonce() {
        byte[] nonce = new byte[GCM_NONCE_LENGTH];
        SECURE_RANDOM.nextBytes(nonce);
        return nonce;
    }
}
