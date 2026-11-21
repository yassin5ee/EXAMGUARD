package tn.supcom.examguard.api;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * Active Jakarta REST sans web.xml.
 *
 * <p>Le WAR est le {@code default-web-module} de son hote virtuel Undertow : il est
 * donc servi a la racine de son sous-domaine. Le chemin {@code /api} rend les
 * routes conformes aux exigences qui les nomment ainsi (ES-AUT-01 : aucune ressource
 * sous {@code /api/} accessible sans session, hormis la connexion, le defi
 * anti-automatisation et {@code /api/health}).
 */
@ApplicationPath("/api")
public class JaxRsActivator extends Application {
}
