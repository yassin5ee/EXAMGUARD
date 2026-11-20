package tn.supcom.examguard.iam.health;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Sonde de disponibilite.
 *
 * <p>{@code @PermitAll} est ici une decision, pas un oubli : c'est l'une des trois
 * exceptions nommees par ES-AUT-01, et ES-DSP-03 mesure la disponibilite par sonde
 * sur cette route pendant les fenetres de distribution. La reponse ne divulgue ni
 * version, ni nom de serveur, ni etat interne (ES-ERR-01).
 */
@Path("/health")
public class HealthResource {

    @GET
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    public Response liveness() {
        return Response.ok("{\"status\":\"UP\"}").build();
    }
}
