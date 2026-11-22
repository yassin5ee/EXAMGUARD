package tn.supcom.examguard.marking.fingerprint;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Word positions in physical PDF points and the glyphs that can be moved. */
final class PdfWordLayout {
    static final double MAX_SHIFT_POINTS = 0.40;
    private static final double BASELINE_TOLERANCE = 0.25;
    private static final double MIN_REGION_GAP = 24;

    private final PDDocument document;
    private final List<PdfPageText> pages;
    private final List<Line> lines;

    private PdfWordLayout(PDDocument document, List<PdfPageText> pages, List<Line> lines) {
        this.document = document;
        this.pages = pages;
        this.lines = List.copyOf(lines);
    }

    static PdfWordLayout read(PDDocument document) throws IOException {
        var catalog = document.getDocumentCatalog().getCOSObject();
        if (document.isEncrypted() || catalog.containsKey(COSName.OCPROPERTIES)
                || catalog.containsKey(COSName.ACRO_FORM)) {
            throw new UnsupportedLayoutException("Le document contient du chiffrement, des calques ou un formulaire");
        }
        List<PdfPageText> pages = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        for (PDPage page : document.getPages()) {
            PdfPageText text = PdfPageText.read(page);
            addLines(pages.size(), text.glyphs(), lines);
            pages.add(text);
        }
        return new PdfWordLayout(document, pages, lines);
    }

    int pageCount() {
        return pages.size();
    }

    List<Line> lines() {
        return lines;
    }

    void shift(Map<Word, Double> shifts) throws IOException {
        Map<Glyph, Double> glyphShifts = new IdentityHashMap<>();
        for (Line line : lines) {
            for (Word word : line.words()) {
                Double shift = shifts.get(word);
                if (shift != null) {
                    if (!word.movable() || !Double.isFinite(shift) || Math.abs(shift) > MAX_SHIFT_POINTS) {
                        throw new IllegalArgumentException("Decalage invalide pour ce mot");
                    }
                    for (Glyph glyph : word.glyphs()) {
                        glyphShifts.put(glyph, shift);
                    }
                }
            }
        }
        for (PdfPageText page : pages) {
            page.shift(document, glyphShifts);
        }
    }

    private static void addLines(int pageIndex, List<Glyph> glyphs, List<Line> lines) throws IOException {
        glyphs.sort(Comparator.comparingDouble(Glyph::baseline).reversed());
        for (int first = 0; first < glyphs.size();) {
            double baseline = glyphs.get(first).baseline();
            int end = first + 1;
            while (end < glyphs.size() && baseline - glyphs.get(end).baseline() <= BASELINE_TOLERANCE) {
                end++;
            }
            addLineSegments(pageIndex, baseline, glyphs.subList(first, end), lines);
            first = end;
        }
    }

    private static void addLineSegments(int pageIndex, double baseline, List<Glyph> row, List<Line> lines)
            throws IOException {
        row.sort(Comparator.comparingDouble(Glyph::startX));
        List<Word> words = new ArrayList<>();
        List<Glyph> word = new ArrayList<>();
        Glyph previous = null;
        boolean space = false;
        for (Glyph glyph : row) {
            if (glyph.space()) {
                space = true;
                continue;
            }
            if (previous != null) {
                double gap = glyph.startX() - previous.endX();
                double spaceWidth = Math.min(previous.spaceWidth(), glyph.spaceWidth());
                double smallestGlyph = Math.min(previous.endX() - previous.startX(), glyph.endX() - glyph.startX());
                if (gap < -0.5 * smallestGlyph) {
                    throw new UnsupportedLayoutException("Des glyphes se superposent sur une meme ligne");
                }
                boolean newSegment = gap > Math.max(MIN_REGION_GAP, 6 * spaceWidth);
                if (newSegment || space || gap > Math.max(0.8, 0.55 * spaceWidth)) {
                    words.add(Word.from(word));
                    word.clear();
                }
                if (newSegment) {
                    lines.add(new Line(pageIndex, baseline, List.copyOf(words)));
                    words.clear();
                }
            }
            word.add(glyph);
            previous = glyph;
            space = false;
        }
        if (!word.isEmpty()) {
            words.add(Word.from(word));
            lines.add(new Line(pageIndex, baseline, List.copyOf(words)));
        }
    }

    record Line(int pageIndex, double baseline, List<Word> words) {
    }

    record Word(String text, double startX, double endX, boolean movable, List<Glyph> glyphs) {
        private static Word from(List<Glyph> glyphs) {
            StringBuilder text = new StringBuilder();
            boolean movable = true;
            for (Glyph glyph : glyphs) {
                text.append(glyph.text());
                movable &= glyph.movable();
            }
            return new Word(text.toString(), glyphs.getFirst().startX(), glyphs.getLast().endX(), movable,
                    List.copyOf(glyphs));
        }
    }

    record Glyph(String text, boolean space, double startX, double endX, double baseline, double scaleX,
                 double spaceWidth, boolean movable, int byteOffset) {
    }

    static final class UnsupportedLayoutException extends IOException {
        UnsupportedLayoutException(String message) {
            super(message);
        }
    }
}
