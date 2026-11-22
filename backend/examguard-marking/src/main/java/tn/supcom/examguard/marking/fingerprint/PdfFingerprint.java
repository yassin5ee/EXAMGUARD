package tn.supcom.examguard.marking.fingerprint;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import tn.supcom.examguard.marking.fingerprint.PdfWordLayout.Line;
import tn.supcom.examguard.marking.fingerprint.PdfWordLayout.Word;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Embeds and reads the inter-word fingerprint described in Annex A.1. */
public final class PdfFingerprint {
    public static final int MAX_PDF_BYTES = 20 * 1024 * 1024;

    private static final int MAX_PAGES = 50;
    private static final int BIT_COUNT = FingerprintCode.BIT_LENGTH;
    private static final int MIN_CARRIERS = 3 * BIT_COUNT;
    private static final int MIN_LINE_WORDS = 6;
    private static final double SHIFT_POINTS = PdfWordLayout.MAX_SHIFT_POINTS;
    private static final double WRITE_TOLERANCE = 0.03;
    private static final double LINE_WIDTH_TOLERANCE = 0.01;
    private static final double REFERENCE_TOLERANCE = 0.10;
    private static final double VOTE_TOLERANCE = 0.15;
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private PdfFingerprint() {
    }

    public static byte[] embed(byte[] original, long code) throws PdfNotMarkableException {
        if (!FingerprintCode.isValid(code)) {
            throw new IllegalArgumentException("Invalid fingerprint checksum");
        }
        try (PDDocument source = load(original)) {
            if (!source.getSignatureDictionaries().isEmpty()) {
                throw new PdfNotMarkableException("Le PDF contient une signature numerique");
            }

            PdfWordLayout originalLayout = PdfWordLayout.read(source);
            CarrierPlan plan = planCarriers(originalLayout);
            if (plan.count() < MIN_CARRIERS) {
                throw new PdfNotMarkableException("Au moins 192 positions de mots modifiables sont requises");
            }

            String originalText = extractText(source);
            Map<Word, Double> shifts = offsets(originalLayout, plan, code);
            originalLayout.shift(shifts);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            source.save(output);
            byte[] marked = output.toByteArray();
            try (PDDocument result = load(marked)) {
                if (!originalText.equals(extractText(result))
                        || !preservesGeometry(originalLayout, PdfWordLayout.read(result), shifts)) {
                    throw new PdfNotMarkableException("Le marquage ne preserve pas le texte ou la mise en page");
                }
            }
            return marked;
        } catch (PdfNotMarkableException e) {
            throw e;
        } catch (IOException e) {
            throw new PdfNotMarkableException("La mise en page de ce PDF ne permet pas un marquage sur", e);
        }
    }

    /** A valid code is a clue. The caller must compare it with registered distributions. */
    public static Optional<Detection> detect(byte[] original, byte[] candidate) {
        try (PDDocument source = load(original); PDDocument suspect = load(candidate)) {
            if (!MessageDigest.isEqual(normalizedTextHash(source), normalizedTextHash(suspect))) {
                return Optional.empty();
            }

            PdfWordLayout originalLayout = PdfWordLayout.read(source);
            PdfWordLayout suspectLayout = PdfWordLayout.read(suspect);
            CarrierPlan plan = planCarriers(originalLayout);
            if (plan.count() < MIN_CARRIERS || !sameWords(originalLayout, suspectLayout)) {
                return Optional.empty();
            }
            return decode(originalLayout, suspectLayout, plan);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static CarrierPlan planCarriers(PdfWordLayout layout) {
        List<BitSet> perLine = new ArrayList<>();
        int carriers = 0;
        for (Line line : layout.lines()) {
            List<Word> words = line.words();
            BitSet selected = new BitSet(words.size());
            if (words.size() >= MIN_LINE_WORDS) {
                for (int i = 1; i < words.size() - 1; i += 2) {
                    Word word = words.get(i);
                    double left = word.startX() - words.get(i - 1).endX();
                    double right = words.get(i + 1).startX() - word.endX();
                    if (word.movable() && Math.min(left, right) > SHIFT_POINTS + WRITE_TOLERANCE) {
                        selected.set(i);
                    }
                }
            }
            perLine.add(selected);
            carriers += selected.cardinality();
        }
        return new CarrierPlan(perLine, carriers);
    }

    private static Map<Word, Double> offsets(PdfWordLayout layout, CarrierPlan plan, long code) {
        Map<Word, Double> shifts = new IdentityHashMap<>();
        int carrier = 0;
        for (int i = 0; i < layout.lines().size(); i++) {
            List<Word> words = layout.lines().get(i).words();
            BitSet selected = plan.perLine().get(i);
            for (int j = selected.nextSetBit(0); j >= 0; j = selected.nextSetBit(j + 1)) {
                int shift = BIT_COUNT - 1 - carrier++ % BIT_COUNT;
                shifts.put(words.get(j), ((code >>> shift) & 1) == 1 ? SHIFT_POINTS : -SHIFT_POINTS);
            }
        }
        return shifts;
    }

    private static Optional<Detection> decode(PdfWordLayout original, PdfWordLayout suspect, CarrierPlan plan) {
        int[] zeros = new int[BIT_COUNT];
        int[] ones = new int[BIT_COUNT];
        int carrier = 0;
        for (int i = 0; i < original.lines().size(); i++) {
            BitSet selected = plan.perLine().get(i);
            if (selected.isEmpty()) {
                continue;
            }
            List<Word> sourceWords = original.lines().get(i).words();
            List<Word> suspectWords = suspect.lines().get(i).words();
            LineFit fit = fitReferences(sourceWords, suspectWords, selected);
            for (int j = selected.nextSetBit(0); j >= 0; j = selected.nextSetBit(j + 1)) {
                int bit = carrier++ % BIT_COUNT;
                if (fit == null) {
                    continue;
                }
                Word sourceWord = sourceWords.get(j);
                Word suspectWord = suspectWords.get(j);
                double left = fit.offset(sourceWord.startX(), suspectWord.startX());
                double right = fit.offset(sourceWord.endX(), suspectWord.endX());
                if (near(left, SHIFT_POINTS, VOTE_TOLERANCE) && near(right, SHIFT_POINTS, VOTE_TOLERANCE)) {
                    ones[bit]++;
                } else if (near(left, -SHIFT_POINTS, VOTE_TOLERANCE) && near(right, -SHIFT_POINTS, VOTE_TOLERANCE)) {
                    zeros[bit]++;
                }
            }
        }

        long code = 0;
        int agreement = 0;
        for (int bit = 0; bit < BIT_COUNT; bit++) {
            int expected = (plan.count() + BIT_COUNT - 1 - bit) / BIT_COUNT;
            int majority = Math.max(ones[bit], zeros[bit]);
            // Missing or unreliable carriers count against the majority.
            if (majority <= expected / 2) {
                return Optional.empty();
            }
            code = (code << 1) | (ones[bit] > zeros[bit] ? 1L : 0L);
            agreement += majority;
        }
        return FingerprintCode.isValid(code)
                ? Optional.of(new Detection(code, (double) agreement / plan.count()))
                : Optional.empty();
    }

    private static LineFit fitReferences(List<Word> source, List<Word> suspect, BitSet carriers) {
        double sumSource = 0;
        double sumSuspect = 0;
        int references = 0;
        for (int i = 0; i < source.size(); i++) {
            if (!carriers.get(i)) {
                sumSource += source.get(i).startX();
                sumSuspect += suspect.get(i).startX();
                references++;
            }
        }
        double meanSource = sumSource / references;
        double meanSuspect = sumSuspect / references;
        double variance = 0;
        double covariance = 0;
        for (int i = 0; i < source.size(); i++) {
            if (!carriers.get(i)) {
                double x = source.get(i).startX() - meanSource;
                variance += x * x;
                covariance += x * (suspect.get(i).startX() - meanSuspect);
            }
        }

        double scale = covariance / variance;
        if (!Double.isFinite(scale) || scale <= 0) {
            return null;
        }
        LineFit fit = new LineFit(scale, meanSuspect - scale * meanSource);
        for (int i = 0; i < source.size(); i++) {
            if (!carriers.get(i) && (!near(fit.offset(source.get(i).startX(), suspect.get(i).startX()),
                    0, REFERENCE_TOLERANCE) || !near(fit.offset(source.get(i).endX(), suspect.get(i).endX()),
                    0, REFERENCE_TOLERANCE))) {
                return null;
            }
        }
        return fit;
    }

    private static boolean preservesGeometry(PdfWordLayout original, PdfWordLayout marked, Map<Word, Double> shifts) {
        if (!sameWords(original, marked)) {
            return false;
        }
        for (int i = 0; i < original.lines().size(); i++) {
            Line source = original.lines().get(i);
            Line result = marked.lines().get(i);
            if (!near(source.baseline(), result.baseline(), WRITE_TOLERANCE)
                    || !near(lineWidth(source), lineWidth(result), LINE_WIDTH_TOLERANCE)) {
                return false;
            }
            for (int j = 0; j < source.words().size(); j++) {
                Word before = source.words().get(j);
                Word after = result.words().get(j);
                double shift = shifts.getOrDefault(before, 0.0);
                if (!near(after.startX() - before.startX(), shift, WRITE_TOLERANCE)
                        || !near(after.endX() - before.endX(), shift, WRITE_TOLERANCE)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double lineWidth(Line line) {
        return line.words().getLast().endX() - line.words().getFirst().startX();
    }

    private static boolean sameWords(PdfWordLayout first, PdfWordLayout second) {
        if (first.pageCount() != second.pageCount() || first.lines().size() != second.lines().size()) {
            return false;
        }
        for (int i = 0; i < first.lines().size(); i++) {
            Line left = first.lines().get(i);
            Line right = second.lines().get(i);
            if (left.pageIndex() != right.pageIndex() || left.words().size() != right.words().size()) {
                return false;
            }
            for (int j = 0; j < left.words().size(); j++) {
                if (!left.words().get(j).text().equals(right.words().get(j).text())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean near(double actual, double expected, double tolerance) {
        return Math.abs(actual - expected) <= tolerance;
    }

    private static PDDocument load(byte[] pdf) throws IOException {
        if (pdf.length == 0 || pdf.length > MAX_PDF_BYTES) {
            throw new PdfNotMarkableException("Taille du PDF hors des limites de marquage");
        }
        PDDocument document = Loader.loadPDF(pdf);
        int pages = document.getNumberOfPages();
        if (document.isEncrypted() || pages < 1 || pages > MAX_PAGES) {
            document.close();
            throw new PdfNotMarkableException("Un PDF non chiffre de 1 a 50 pages est requis");
        }
        return document;
    }

    private static String extractText(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        return stripper.getText(document);
    }

    private static byte[] normalizedTextHash(PDDocument document) throws IOException {
        String text = Normalizer.normalize(extractText(document), Normalizer.Form.NFC);
        String normalized = SPACE.matcher(text).replaceAll(" ").strip();
        try {
            return MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /** Agreement is the fraction of planned carrier positions supporting the recovered code. */
    public record Detection(long code, double agreement) {
    }

    public static final class PdfNotMarkableException extends IOException {
        private PdfNotMarkableException(String message) {
            super(message);
        }

        private PdfNotMarkableException(String message, IOException cause) {
            super(message, cause);
        }
    }

    private record CarrierPlan(List<BitSet> perLine, int count) {
    }

    private record LineFit(double scale, double translation) {
        double offset(double before, double after) {
            return (after - translation) / scale - before;
        }
    }
}
