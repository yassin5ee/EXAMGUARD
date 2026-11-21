package tn.supcom.examguard.archtests;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Regles d'architecture portant sur le controle d'acces declaratif.
 *
 * <p>Le refus par defaut ne peut pas etre garanti par relecture : il suffit d'un
 * endpoint ajoute sans annotation pour que la valeur par defaut du conteneur
 * s'applique. Cette regle transforme donc une consigne de revue en barriere
 * mecanique, executee a chaque construction.
 */
public final class SecurityAnnotationRules {

    /** Les annotations qui font d'une methode une ressource REST exposee. */
    private static final List<String> JAKARTA_REST_HTTP_METHODS = List.of(
            "jakarta.ws.rs.GET",
            "jakarta.ws.rs.POST",
            "jakarta.ws.rs.PUT",
            "jakarta.ws.rs.DELETE",
            "jakarta.ws.rs.PATCH",
            "jakarta.ws.rs.HEAD",
            "jakarta.ws.rs.OPTIONS");

    /** Les trois seules facons admises de declarer une intention d'acces. */
    private static final List<String> ACCESS_CONTROL_ANNOTATIONS = List.of(
            "jakarta.annotation.security.RolesAllowed",
            "jakarta.annotation.security.DenyAll",
            "jakarta.annotation.security.PermitAll");

    /**
     * ES-AUZ-01 : « chaque methode de ressource REST declare les roles autorises ».
     *
     * <p>L'exigence est volontairement lue au sens strict : une annotation portee par
     * la classe ne satisfait pas la regle. Jakarta Security autoriserait l'heritage
     * depuis la classe, mais l'ajout ulterieur d'une methode dans une classe deja
     * annotee passerait alors inapercu, et c'est precisement le scenario d'oubli que
     * cette regle existe pour attraper. {@code @PermitAll} explicite reste disponible
     * pour les routes publiques assumees.
     */
    public static final ArchRule REST_METHODS_DECLARE_ACCESS_CONTROL =
            methods()
                    .that(areJakartaRestResourceMethods())
                    .should(declareAccessControlOnTheMethod())
                    .as("ES-AUZ-01 : toute methode de ressource Jakarta REST declare "
                            + "explicitement son controle d'acces");

    private SecurityAnnotationRules() {
        throw new AssertionError("classe de regles, non instanciable");
    }

    private static DescribedPredicate<JavaMethod> areJakartaRestResourceMethods() {
        return DescribedPredicate.describe(
                "des methodes de ressource Jakarta REST",
                method -> JAKARTA_REST_HTTP_METHODS.stream()
                        .anyMatch(annotation -> method.isAnnotatedWith(annotation)));
    }

    private static ArchCondition<JavaMethod> declareAccessControlOnTheMethod() {
        return new ArchCondition<JavaMethod>(
                "porter @RolesAllowed, @DenyAll ou @PermitAll sur la methode elle-meme") {

            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean declared = ACCESS_CONTROL_ANNOTATIONS.stream()
                        .anyMatch(annotation -> method.isAnnotatedWith(annotation));

                String message = declared
                        ? method.getFullName() + " declare son controle d'acces"
                        : method.getFullName()
                                + " est un endpoint REST sans @RolesAllowed, @DenyAll ni @PermitAll "
                                + "sur la methode : le refus par defaut n'est pas garanti (ES-AUZ-01)";

                events.add(new SimpleConditionEvent(method, declared, message));
            }
        };
    }
}
