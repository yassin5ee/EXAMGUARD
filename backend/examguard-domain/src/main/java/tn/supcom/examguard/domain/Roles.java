package tn.supcom.examguard.domain;

/**
 * Les quatre roles d'ExamGuard, sous forme de constantes de compilation.
 *
 * <p>{@code @RolesAllowed} n'accepte que des litteraux {@code String} : passer par ces
 * constantes rend une faute de frappe non compilable, alors qu'un litteral errone
 * produirait un endpoint silencieusement inaccessible, ou pire, mal protege.
 *
 * <p>Un compte ne porte qu'un seul role (ES-AUZ-03, separation des pouvoirs).
 */
public final class Roles {

    /** Cree une session d'examen, depose le sujet, valide la distribution. */
    public static final String ENSEIGNANT = "ENSEIGNANT";

    /** Telecharge son unique exemplaire, pendant la fenetre de distribution. */
    public static final String RESPONSABLE_EXAMEN = "RESPONSABLE_EXAMEN";

    /** Analyse un fichier divulgue et consulte les traces. Second facteur obligatoire. */
    public static final String AUDITEUR = "AUDITEUR";

    /** Gere les comptes et les roles. Aucun acces aux sujets ni a l'investigation. */
    public static final String ADMINISTRATEUR = "ADMINISTRATEUR";

    private Roles() {
        throw new AssertionError("classe de constantes, non instanciable");
    }
}
