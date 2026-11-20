package tn.supcom.examguard.archtests;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Applique les regles d'architecture aux deux deployables, api-1.0.war et iam-1.0.war.
 *
 * <p>Si aucune classe n'est trouvee dans le perimetre, ou si la regle ne selectionne
 * aucune methode, ArchUnit echoue au lieu de passer a vide. Une erreur dans la regle
 * elle-meme ne peut donc pas rendre la barriere silencieusement inoperante.
 */
@AnalyzeClasses(packages = {
        "tn.supcom.examguard.api",
        "tn.supcom.examguard.iam"
})
class RestEndpointSecurityTest {

    @ArchTest
    static final ArchRule rest_endpoints_declare_access_control =
            SecurityAnnotationRules.REST_METHODS_DECLARE_ACCESS_CONTROL;
}
