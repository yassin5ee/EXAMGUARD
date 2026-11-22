package tn.supcom.examguard.marking.fingerprint;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import tn.supcom.examguard.marking.fingerprint.PdfWordLayout.Glyph;
import tn.supcom.examguard.marking.fingerprint.PdfWordLayout.UnsupportedLayoutException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** A text show operator and the byte boundaries of the glyphs in each operand. */
final class PdfTextShow {
    private final Operator operator;
    private final COSBase argument;
    private final List<COSBase> parts = new ArrayList<>();
    private final List<EncodedText> strings = new ArrayList<>();
    private int nextString;

    PdfTextShow(Operator operator, List<COSBase> operands) throws UnsupportedLayoutException {
        this.operator = operator;
        if (operands.size() != 1) {
            throw malformedText();
        }
        argument = operands.getFirst();
        if (OperatorName.SHOW_TEXT.equals(operator.getName()) && argument instanceof COSString string) {
            addPart(string);
        } else if (OperatorName.SHOW_TEXT_ADJUSTED.equals(operator.getName()) && argument instanceof COSArray array) {
            for (COSBase part : array) {
                addPart(part);
            }
        } else {
            throw malformedText();
        }
    }

    private void addPart(COSBase part) throws UnsupportedLayoutException {
        if (part instanceof COSString string) {
            strings.add(new EncodedText(string));
        } else if (!(part instanceof COSNumber number) || !Float.isFinite(number.floatValue())) {
            throw malformedText();
        }
        parts.add(part);
    }

    List<Glyph> glyphsFor(byte[] bytes) throws IOException {
        if (nextString >= strings.size()) {
            throw malformedText();
        }
        EncodedText encoded = strings.get(nextString++);
        if (!Arrays.equals(encoded.original.getBytes(), bytes)) {
            throw malformedText();
        }
        return encoded.glyphs;
    }

    void finish() throws IOException {
        if (nextString != strings.size()) {
            throw malformedText();
        }
    }

    boolean hasShifts(Map<Glyph, Double> shifts) {
        return strings.stream().anyMatch(string -> string.hasShifts(shifts));
    }

    void write(ContentStreamWriter writer, Map<Glyph, Double> shifts) throws IOException {
        if (!hasShifts(shifts)) {
            writer.writeTokens(argument, operator);
            return;
        }

        COSArray rewritten = new COSArray();
        int stringIndex = 0;
        for (COSBase part : parts) {
            if (part instanceof COSString) {
                strings.get(stringIndex++).append(rewritten, shifts);
            } else {
                rewritten.add(part);
            }
        }
        writer.writeTokens(rewritten, Operator.getOperator(OperatorName.SHOW_TEXT_ADJUSTED));
    }

    private static UnsupportedLayoutException malformedText() {
        return new UnsupportedLayoutException("Les operateurs de texte ont des arguments invalides");
    }

    private static final class EncodedText {
        private final COSString original;
        private final List<Glyph> glyphs = new ArrayList<>();

        private EncodedText(COSString original) {
            this.original = original;
        }

        private boolean hasShifts(Map<Glyph, Double> shifts) {
            return glyphs.stream().anyMatch(shifts::containsKey);
        }

        private void append(COSArray result, Map<Glyph, Double> shifts) {
            if (!hasShifts(shifts)) {
                result.add(original);
                return;
            }
            byte[] bytes = original.getBytes();
            int from = 0;
            double offset = 0;
            double scale = glyphs.getFirst().scaleX();
            for (Glyph glyph : glyphs) {
                double nextOffset = shifts.getOrDefault(glyph, 0.0);
                if (offset != nextOffset) {
                    appendBytes(result, bytes, from, glyph.byteOffset());
                    result.add(adjustment(nextOffset - offset, scale));
                    from = glyph.byteOffset();
                    offset = nextOffset;
                }
            }
            appendBytes(result, bytes, from, bytes.length);
            if (offset != 0) {
                // Restore the original text cursor before the next element or operator.
                result.add(adjustment(-offset, scale));
            }
        }

        private static COSFloat adjustment(double points, double scale) {
            return new COSFloat((float) (-1000 * points / scale));
        }

        private static void appendBytes(COSArray result, byte[] bytes, int from, int to) {
            if (from < to) {
                result.add(new COSString(Arrays.copyOfRange(bytes, from, to)));
            }
        }
    }
}
