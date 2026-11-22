package tn.supcom.examguard.api.copies;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExamCopyStoreTest {
    @Test
    void issuedPdfVerifiesAndOneChangedByteDoesNot() throws Exception {
        ExamCopyStore store = new ExamCopyStore();
        byte[] exam = samplePdf();
        UUID id = store.issue("amal", exam).orElseThrow();

        byte[] downloaded = store.download(id, "amal").orElseThrow();
        assertArrayEquals(exam, downloaded);
        assertEquals(ExamCopyStore.Verdict.VALID, store.verify(id, downloaded).orElseThrow());

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

    private static byte[] samplePdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }
}
