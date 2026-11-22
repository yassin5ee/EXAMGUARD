package tn.supcom.examguard.marking.fingerprint;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfFingerprintTest {
    private static final List<String> QUESTIONS = List.of(
            "Trace the flow of data from each node and explain how the system checks and logs changes.",
            "Draw the graph for the task and say why the node cannot be reached on that path.",
            "Read the case and list the events that take place when both users send the same request.",
            "State which part of the plan can run in parallel and give a reason for your choice."
    );

    @Test
    void distinguishesTwoCopiesWhilePreservingTextAndLineExtents() throws Exception {
        // Each numbered line has 18 words and eight carriers. The two pages have 104 and 88 carriers.
        byte[] original = sampleExam(13, 11);
        long amalCode = codeFor(1);
        long samiCode = codeFor(2);
        assertNotEquals(amalCode, samiCode);

        byte[] amal = PdfFingerprint.embed(original, amalCode);
        byte[] sami = PdfFingerprint.embed(original, samiCode);
        assertFalse(Arrays.equals(amal, sami));

        Snapshot expected = readSnapshot(original);
        assertEquals(2, expected.pageCount());
        assertEquals(24, expected.extents().size());
        assertSameLayout(expected, readSnapshot(amal));
        assertSameLayout(expected, readSnapshot(sami));
        PdfFingerprint.Detection amalDetection = PdfFingerprint.detect(original, amal).orElseThrow();
        assertEquals(amalCode, amalDetection.code());
        assertEquals(1.0, amalDetection.agreement());
        assertEquals(samiCode, PdfFingerprint.detect(original, sami).orElseThrow().code());
        assertTrue(PdfFingerprint.detect(original, original).isEmpty());
        assertTrue(PdfFingerprint.detect(firstPage(original), firstPage(amal)).isEmpty());
    }

    @Test
    void recoversFingerprintWhenPageContentIsScaledAndTranslated() throws Exception {
        byte[] original = sampleExam(13, 11);
        long code = codeFor(3);
        byte[] copy = PdfFingerprint.embed(original, code);
        byte[] transformed;
        try (PDDocument document = Loader.loadPDF(copy)) {
            for (PDPage page : document.getPages()) {
                try (PDPageContentStream stream = new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.PREPEND, true, false)) {
                    stream.transform(new Matrix(1.03f, 0, 0, 1, 6, 0));
                }
            }
            transformed = save(document);
        }

        assertEquals(code, PdfFingerprint.detect(original, transformed).orElseThrow().code());
    }

    @Test
    void rejectsAnExamWithTooFewCarriers() throws Exception {
        byte[] shortExam = sampleExam(13, 10);
        long code = codeFor(1);

        assertThrows(PdfFingerprint.PdfNotMarkableException.class, () -> PdfFingerprint.embed(shortExam, code));
    }

    private static long codeFor(long distribution) throws GeneralSecurityException {
        // Fixed identifier and dummy key for repeatable local tests.
        return FingerprintCode.derive(new SecretKeySpec(new byte[32], "HmacSHA256"),
                new UUID(0, 1), new UUID(0, distribution));
    }

    private static byte[] sampleExam(int... linesPerPage) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int pageIndex = 0; pageIndex < linesPerPage.length; pageIndex++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, 10);
                    stream.setLeading(18);
                    stream.newLineAtOffset(54, 720);
                    for (int lineIndex = 0; lineIndex < linesPerPage[pageIndex]; lineIndex++) {
                        String line = (lineIndex + 1) + ". " + QUESTIONS.get(lineIndex % QUESTIONS.size());
                        if (pageIndex % 2 == 0) {
                            stream.showText(line);
                        } else {
                            int split = line.indexOf(' ', line.length() / 2) + 1;
                            stream.showTextWithPositioning(new Object[]{line.substring(0, split), -35f,
                                    line.substring(split)});
                        }
                        stream.newLine();
                    }
                    stream.endText();
                }
            }
            return save(document);
        }
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }

    private static byte[] firstPage(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            while (document.getNumberOfPages() > 1) {
                document.removePage(1);
            }
            return save(document);
        }
    }

    private static Snapshot readSnapshot(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            LayoutReader reader = new LayoutReader();
            String text = reader.getText(document);
            return new Snapshot(document.getNumberOfPages(), text, Map.copyOf(reader.extents),
                    List.copyOf(reader.glyphPositions));
        }
    }

    private static void assertSameLayout(Snapshot original, Snapshot marked) {
        assertEquals(original.pageCount(), marked.pageCount());
        assertEquals(original.text(), marked.text());
        assertEquals(original.extents().keySet(), marked.extents().keySet());
        original.extents().forEach((line, expected) -> {
            Extent actual = marked.extents().get(line);
            assertEquals(expected.left(), actual.left(), 0.01, "Left edge of " + line);
            assertEquals(expected.right(), actual.right(), 0.01, "Right edge of " + line);
            assertEquals(expected.right() - expected.left(), actual.right() - actual.left(), 0.01,
                    "Width of " + line);
        });
        assertEquals(original.glyphPositions().size(), marked.glyphPositions().size());
        double maxShift = 0;
        for (int i = 0; i < original.glyphPositions().size(); i++) {
            double shift = marked.glyphPositions().get(i) - original.glyphPositions().get(i);
            maxShift = Math.max(maxShift, Math.abs(shift));
        }
        assertEquals(0.40, maxShift, 0.02, "Maximum glyph displacement in points");
    }

    private record Snapshot(int pageCount, String text, Map<LinePosition, Extent> extents,
                            List<Float> glyphPositions) {
    }

    private record LinePosition(int page, float baseline) {
    }

    private record Extent(float left, float right) {
        Extent union(Extent other) {
            return new Extent(Math.min(left, other.left), Math.max(right, other.right));
        }
    }

    private static final class LayoutReader extends PDFTextStripper {
        private final Map<LinePosition, Extent> extents = new HashMap<>();
        private final List<Float> glyphPositions = new ArrayList<>();

        LayoutReader() throws IOException {
            setSortByPosition(true);
        }

        @Override
        protected void processTextPosition(TextPosition position) {
            float left = position.getXDirAdj();
            glyphPositions.add(left);
            extents.merge(new LinePosition(getCurrentPageNo(), position.getYDirAdj()),
                    new Extent(left, left + position.getWidthDirAdj()), Extent::union);
            super.processTextPosition(position);
        }
    }
}
