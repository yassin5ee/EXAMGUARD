package tn.supcom.examguard.api.copies;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExamCopyStoreTest {
    @Test
    void issuedPdfVerifiesAndOneChangedByteDoesNot() throws Exception {
        ExamCopyStore store = new ExamCopyStore();
        byte[] exam = samplePdf();
        UUID id = store.issue("amal", exam).orElseThrow();

        byte[] downloaded = store.download(id, "amal").orElseThrow();
        assertEquals(ExamCopyStore.Verdict.VALID, store.verify(id, downloaded).orElseThrow());
        assertEquals(ExamCopyStore.Verdict.HASH_MISMATCH, store.verify(id, exam).orElseThrow());

        byte[] changed = downloaded.clone();
        changed[changed.length - 1] ^= 1;
        assertEquals(ExamCopyStore.Verdict.HASH_MISMATCH, store.verify(id, changed).orElseThrow());
    }

    @Test
    void downloadRequiresTheAssignedRecipientAndAnExistingId() throws Exception {
        ExamCopyStore store = new ExamCopyStore();
        UUID id = store.issue("amal", samplePdf()).orElseThrow();

        assertTrue(store.download(id, "sami").isEmpty());
        assertTrue(store.download(UUID.randomUUID(), "amal").isEmpty());
    }

    @Test
    void identifiesTwoDistributionsWithoutProvidingACopyId() throws Exception {
        ExamCopyStore store = new ExamCopyStore();
        byte[] exam = samplePdf();
        UUID amal = store.issue("amal", exam).orElseThrow();
        UUID sami = store.issue("sami", exam).orElseThrow();
        byte[] amalPdf = store.download(amal, "amal").orElseThrow();
        byte[] samiPdf = store.download(sami, "sami").orElseThrow();

        assertFalse(Arrays.equals(amalPdf, samiPdf));
        assertEquals(new ExamCopyStore.Identification(amal, ExamCopyStore.Evidence.EXACT_MATCH),
                store.identify(amalPdf).orElseThrow());
        assertEquals(new ExamCopyStore.Identification(sami, ExamCopyStore.Evidence.EXACT_MATCH),
                store.identify(samiPdf).orElseThrow());
        assertEquals(new ExamCopyStore.Identification(amal, ExamCopyStore.Evidence.FINGERPRINT_HINT),
                store.identify(resave(amalPdf)).orElseThrow());
        assertEquals(new ExamCopyStore.Identification(sami, ExamCopyStore.Evidence.FINGERPRINT_HINT),
                store.identify(resave(samiPdf)).orElseThrow());
        assertTrue(store.identify(exam).isEmpty());
        assertTrue(store.identify(new byte[]{0}).isEmpty());
    }

    private static byte[] samplePdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int pageIndex = 0; pageIndex < 4; pageIndex++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 11);
                    content.setLeading(18);
                    content.newLineAtOffset(45, 700);
                    for (int line = 0; line < 8; line++) {
                        content.showText("Question" + (pageIndex * 8 + line + 1)
                                + " all answers should explain your reasoning in clear steps for this written exam.");
                        content.newLine();
                    }
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] resave(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.getDocumentInformation().setTitle("Re-saved for an investigation");
            document.save(output);
            return output.toByteArray();
        }
    }
}
