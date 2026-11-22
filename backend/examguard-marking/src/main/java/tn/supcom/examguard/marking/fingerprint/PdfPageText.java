package tn.supcom.examguard.marking.fingerprint;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import tn.supcom.examguard.marking.fingerprint.PdfWordLayout.Glyph;
import tn.supcom.examguard.marking.fingerprint.PdfWordLayout.UnsupportedLayoutException;

import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads positioning and clipping state without rasterizing the page. */
final class PdfPageText extends PDFGraphicsStreamEngine {
    private static final double MOVE_MARGIN = 0.50;
    private static final double MIN_TEXT_SCALE = 0.01;
    private static final double AXIS_TOLERANCE = 0.0001;

    private final double userUnit;
    private final List<Object> tokens = new ArrayList<>();
    private final List<Glyph> glyphs = new ArrayList<>();
    private final GeneralPath path = new GeneralPath();
    private int pendingClipRule = -1;
    private PdfTextShow textOperator;
    private ByteArrayInputStream currentBytes;
    private int currentLength;
    private List<Glyph> currentGlyphs;
    private double spaceAdvance;

    private PdfPageText(PDPage page) {
        super(page);
        userUnit = page.getUserUnit();
    }

    static PdfPageText read(PDPage page) throws IOException {
        if (page.getRotation() != 0 || !Float.isFinite(page.getUserUnit()) || page.getUserUnit() <= 0) {
            throw new UnsupportedLayoutException("Pages tournees ou echelle de page invalide");
        }
        PdfPageText text = new PdfPageText(page);
        text.processPage(page);
        return text;
    }

    List<Glyph> glyphs() {
        return glyphs;
    }

    void shift(PDDocument document, Map<Glyph, Double> shifts) throws IOException {
        if (glyphs.stream().noneMatch(shifts::containsKey)) {
            return;
        }
        PDStream stream = new PDStream(document);
        try (var output = stream.createOutputStream(COSName.FLATE_DECODE)) {
            ContentStreamWriter writer = new ContentStreamWriter(output);
            for (Object token : tokens) {
                switch (token) {
                    case PdfTextShow show -> show.write(writer, shifts);
                    case Operator operator -> writer.writeToken(operator);
                    case COSBase value -> writer.writeToken(value);
                    default -> throw new IllegalStateException("Jeton PDF inattendu");
                }
            }
        }
        getPage().setContents(stream);
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        switch (operator.getName()) {
            case OperatorName.SHOW_TEXT, OperatorName.SHOW_TEXT_ADJUSTED -> {
                textOperator = new PdfTextShow(operator, operands);
                tokens.add(textOperator);
                try {
                    super.processOperator(operator, operands);
                    textOperator.finish();
                } finally {
                    textOperator = null;
                }
            }
            case OperatorName.SHOW_TEXT_LINE -> {
                processOperator(Operator.getOperator(OperatorName.NEXT_LINE), List.of());
                processOperator(Operator.getOperator(OperatorName.SHOW_TEXT), operands);
            }
            case OperatorName.SHOW_TEXT_LINE_AND_SPACE -> {
                if (operands.size() != 3 || !(operands.get(0) instanceof COSNumber)
                        || !(operands.get(1) instanceof COSNumber)) {
                    throw new UnsupportedLayoutException("Espacements de texte invalides");
                }
                processOperator(Operator.getOperator(OperatorName.SET_WORD_SPACING), List.of(operands.get(0)));
                processOperator(Operator.getOperator(OperatorName.SET_CHAR_SPACING), List.of(operands.get(1)));
                processOperator(Operator.getOperator(OperatorName.SHOW_TEXT_LINE), List.of(operands.get(2)));
            }
            case OperatorName.DRAW_OBJECT -> {
                if (operands.size() != 1 || !(operands.getFirst() instanceof COSName name)
                        || getResources() == null || !getResources().isImageXObject(name)) {
                    throw new UnsupportedLayoutException("Un objet de page utilise un formulaire ou une ressource inconnue");
                }
                preserve(operator, operands);
            }
            case OperatorName.BEGIN_INLINE_IMAGE, OperatorName.SHADING_FILL -> preserve(operator, operands);
            default -> {
                preserve(operator, operands);
                super.processOperator(operator, operands);
            }
        }
    }

    @Override
    public void processOperator(String operation, List<COSBase> operands) throws IOException {
        // PDFBox expands operators such as T* into internal calls to Td. Record only the PDF tokens.
        super.processOperator(Operator.getOperator(operation), operands);
    }

    private void preserve(Operator operator, List<COSBase> operands) {
        tokens.addAll(operands);
        tokens.add(operator);
    }

    @Override
    protected void operatorException(Operator operator, List<COSBase> operands, IOException exception)
            throws IOException {
        throw exception;
    }

    @Override
    protected void showText(byte[] bytes) throws IOException {
        var textState = getGraphicsState().getTextState();
        PDFont font = textState.getFont();
        RenderingMode mode = textState.getRenderingMode();
        if (font == null || font.isVertical() || font instanceof PDType3Font || getTextMatrix() == null
                || mode.isClip() || !(mode.isFill() || mode.isStroke())) {
            throw new UnsupportedLayoutException("Police ou mode de rendu du texte non admis");
        }
        currentGlyphs = textOperator.glyphsFor(bytes);
        currentBytes = new ByteArrayInputStream(bytes);
        currentLength = bytes.length;
        spaceAdvance = font.getSpaceWidth() / 1000.0;
        super.showText(bytes);
        if (currentBytes.available() != 0) {
            throw new UnsupportedLayoutException("La police ne permet pas de reperer les limites des glyphes");
        }
    }

    @Override
    protected void showGlyph(Matrix matrix, PDFont font, int code, Vector displacement) throws IOException {
        int remaining = currentBytes.available();
        if (font.readCode(currentBytes) != code || currentBytes.available() >= remaining) {
            throw new UnsupportedLayoutException("Les limites des glyphes sont ambigues");
        }
        String unicode = font.toUnicode(code);
        if (unicode == null || unicode.isEmpty() || unicode.codePoints().anyMatch(PdfPageText::isUnsupportedCharacter)) {
            throw new UnsupportedLayoutException("Le texte ne peut pas etre associe a des glyphes horizontaux");
        }
        boolean space = unicode.codePoints().allMatch(PdfPageText::isSpace);
        if (!space && unicode.codePoints().anyMatch(PdfPageText::isSpace)) {
            throw new UnsupportedLayoutException("Un glyphe contient plusieurs mots");
        }

        double scaleX = matrix.getScaleX() * userUnit;
        double scaleY = matrix.getScaleY() * userUnit;
        double x = matrix.getTranslateX() * userUnit;
        double y = matrix.getTranslateY() * userUnit;
        double width = scaleX * displacement.getX();
        if (!(Double.isFinite(scaleX) && Double.isFinite(scaleY) && scaleX >= MIN_TEXT_SCALE && scaleY >= MIN_TEXT_SCALE
                && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(width) && (space || width > 0)
                && Math.abs(matrix.getShearY()) <= AXIS_TOLERANCE && Math.abs(matrix.getShearX()) <= AXIS_TOLERANCE)) {
            throw new UnsupportedLayoutException("Le texte est tourne, incline ou sans largeur exploitable");
        }
        double spaceWidth = Double.isFinite(spaceAdvance) && spaceAdvance > 0 ? spaceAdvance * scaleX : 0.25 * scaleX;
        boolean movable = coversShiftedGlyph(x, y, width, scaleX, scaleY) && visibleText();
        Glyph glyph = new Glyph(unicode, space, x, x + width, y, scaleX, spaceWidth, movable,
                currentLength - remaining);
        glyphs.add(glyph);
        currentGlyphs.add(glyph);
    }

    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isUnsupportedCharacter(int codePoint) {
        byte direction = Character.getDirectionality(codePoint);
        return Character.isISOControl(codePoint) || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE;
    }

    private boolean visibleText() {
        var state = getGraphicsState();
        RenderingMode mode = state.getTextState().getRenderingMode();
        return state.getSoftMask() == null && ((mode.isFill() && state.getNonStrokeAlphaConstant() > 0)
                || (mode.isStroke() && state.getAlphaConstant() > 0));
    }

    private boolean coversShiftedGlyph(double x, double y, double width, double scaleX, double scaleY) {
        double margin = MOVE_MARGIN + 0.25 * scaleX;
        return getGraphicsState().getCurrentClippingPath().contains((x - margin) / userUnit,
                (y - 0.35 * scaleY) / userUnit, (width + 2 * margin) / userUnit, 1.60 * scaleY / userUnit);
    }

    @Override
    public void appendRectangle(Point2D first, Point2D second, Point2D third, Point2D fourth) {
        path.moveTo(first.getX(), first.getY());
        path.lineTo(second.getX(), second.getY());
        path.lineTo(third.getX(), third.getY());
        path.lineTo(fourth.getX(), fourth.getY());
        path.closePath();
    }

    @Override
    public void drawImage(PDImage image) { }

    @Override
    public void clip(int windingRule) {
        pendingClipRule = windingRule;
    }

    @Override
    public void moveTo(float x, float y) {
        path.moveTo(x, y);
    }

    @Override
    public void lineTo(float x, float y) {
        path.lineTo(x, y);
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        path.curveTo(x1, y1, x2, y2, x3, y3);
    }

    @Override
    public Point2D getCurrentPoint() {
        return path.getCurrentPoint();
    }

    @Override
    public void closePath() {
        path.closePath();
    }

    @Override
    public void endPath() {
        if (pendingClipRule != -1) {
            path.setWindingRule(pendingClipRule);
            getGraphicsState().intersectClippingPath(path);
            pendingClipRule = -1;
        }
        path.reset();
    }

    @Override
    public void strokePath() {
        endPath();
    }

    @Override
    public void fillPath(int windingRule) {
        endPath();
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        endPath();
    }

    @Override
    public void shadingFill(COSName name) { }
}
