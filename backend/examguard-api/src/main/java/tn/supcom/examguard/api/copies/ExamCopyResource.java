package tn.supcom.examguard.api.copies;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import tn.supcom.examguard.domain.Roles;
import tn.supcom.examguard.marking.fingerprint.PdfFingerprint;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.UUID;

@Path("/exam-copies")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class ExamCopyResource {
    private static final String PDF = "application/pdf";
    private static final int MAX_PAGES = 50;

    @Inject
    ExamCopyStore store;

    @POST
    @Consumes(PDF)
    @RolesAllowed(Roles.ENSEIGNANT)
    public Response issue(@QueryParam("recipientId") String recipientId, InputStream body, @Context UriInfo uri)
            throws GeneralSecurityException {
        requireDemoEnabled();
        if (recipientId == null || !recipientId.matches("[A-Za-z0-9_.@-]{1,128}")) {
            throw new BadRequestException("Identifiant destinataire invalide");
        }
        byte[] pdf = readPdfBody(body);
        validateSource(pdf);
        UUID copyId;
        try {
            copyId = store.issue(recipientId, pdf)
                    .orElseThrow(() -> new WebApplicationException("Stockage de demonstration plein", 503));
        } catch (PdfFingerprint.PdfNotMarkableException e) {
            return noStore(Response.status(422).entity(Json.createObjectBuilder()
                    .add("error", "PDF_NOT_MARKABLE").add("message", e.getMessage()).build()));
        }

        URI download = uri.getAbsolutePathBuilder().path(copyId.toString()).path("download").build();
        return noStore(Response.created(download).entity(Json.createObjectBuilder()
                .add("copyId", copyId.toString())
                .add("downloadUrl", download.getRawPath())
                .build()));
    }

    @GET
    @Path("/{copyId}/download")
    @Produces(PDF)
    @RolesAllowed(Roles.RESPONSABLE_EXAMEN)
    public Response download(@PathParam("copyId") String copyId, @Context SecurityContext security)
            throws GeneralSecurityException {
        requireDemoEnabled();
        byte[] pdf = store.download(parseId(copyId), security.getUserPrincipal().getName())
                .orElseThrow(NotFoundException::new);
        return noStore(Response.ok(pdf).header("Content-Disposition", "attachment; filename=\"exam-copy.pdf\""));
    }

    @POST
    @Path("/verify")
    @Consumes(PDF)
    @RolesAllowed(Roles.AUDITEUR)
    public Response verify(@QueryParam("copyId") String copyId, InputStream body) throws GeneralSecurityException {
        requireDemoEnabled();
        UUID id = parseId(copyId);
        ExamCopyStore.Verdict verdict = store.verify(id, readPdfBody(body)).orElseThrow(NotFoundException::new);
        return noStore(Response.ok(Json.createObjectBuilder().add("verdict", verdict.name()).build()));
    }

    @POST
    @Path("/identify")
    @Consumes(PDF)
    @RolesAllowed(Roles.AUDITEUR)
    public Response identify(InputStream body) throws GeneralSecurityException {
        requireDemoEnabled();
        JsonObjectBuilder result = Json.createObjectBuilder();
        store.identify(readPdfBody(body)).ifPresentOrElse(
                match -> result.add("verdict", match.evidence().name()).add("copyId", match.copyId().toString()),
                () -> result.add("verdict", "INDETERMINATE"));
        return noStore(Response.ok(result.build()));
    }

    private static void requireDemoEnabled() {
        if (!Boolean.getBoolean("examguard.copies.demo.enabled")) {
            throw new NotFoundException();
        }
    }

    private static byte[] readPdfBody(InputStream body) {
        if (body == null) {
            throw new BadRequestException("Document manquant");
        }
        byte[] bytes;
        try {
            bytes = body.readNBytes(PdfFingerprint.MAX_PDF_BYTES + 1);
        } catch (IOException e) {
            throw new BadRequestException("Lecture du document impossible");
        }
        if (bytes.length > PdfFingerprint.MAX_PDF_BYTES) {
            throw new WebApplicationException("Document de plus de 20 Mio", 413);
        }
        if (bytes.length == 0) {
            throw new BadRequestException("Document manquant");
        }
        return bytes;
    }

    private static void validateSource(byte[] pdf) {
        if (pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F' || pdf[4] != '-') {
            throw new BadRequestException("Signature PDF manquante");
        }
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            if (document.isEncrypted() || pages < 1 || pages > MAX_PAGES) {
                throw new BadRequestException("Un PDF non chiffre de 1 a 50 pages est requis");
            }
        } catch (IOException e) {
            throw new BadRequestException("PDF illisible ou chiffre");
        }
    }

    private static UUID parseId(String value) {
        if (value == null || value.length() != 36) {
            throw new BadRequestException("Identifiant de copie invalide");
        }
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) {
                return id;
            }
        } catch (IllegalArgumentException ignored) {
            // Meme reponse pour tous les UUID mal formes.
        }
        throw new BadRequestException("Identifiant de copie invalide");
    }

    private static Response noStore(Response.ResponseBuilder response) {
        return response.header("Cache-Control", "no-store").build();
    }
}
