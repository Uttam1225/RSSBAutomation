package com.uttam.paper.service;

import com.uttam.paper.config.ConfigLoader;
import com.uttam.paper.model.Language;
import com.uttam.paper.model.Question;
import com.uttam.paper.model.QuestionPaper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates a formatted PDF question paper using Apache PDFBox.
 *
 * <p>PDF structure:
 * <pre>
 *   ┌─────────────────────────────┐
 *   │  HEADER (title, set, date)  │
 *   ├─────────────────────────────┤
 *   │  INSTRUCTIONS               │
 *   ├─────────────────────────────┤
 *   │  SECTION A – Junior (EN)    │
 *   ├─────────────────────────────┤
 *   │  SECTION B – Senior (EN)    │
 *   ├─────────────────────────────┤
 *   │  SECTION C – Hindi (JR+SR)  │
 *   ├─────────────────────────────┤
 *   │  ANSWER KEY                 │
 *   └─────────────────────────────┘
 * </pre>
 *
 * <p><b>Hindi font note:</b> Devanagari script requires a Unicode TrueType font.
 * Place {@code NotoSansDevanagari-Regular.ttf} in {@code src/main/resources/fonts/}.
 * If absent, Hindi text falls back to Latin encoding (mojibake may occur).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfService {

    private static final DateTimeFormatter FILE_DATE_FMT  = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter PRINT_DATE_FMT = DateTimeFormatter.ofPattern("dd MMMM yyyy");

    // Page geometry
    private static final float PAGE_WIDTH   = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT  = PDRectangle.A4.getHeight();
    private static final float MARGIN_LEFT  = 50f;
    private static final float MARGIN_RIGHT = 50f;
    private static final float MARGIN_TOP   = 50f;
    private static final float MARGIN_BOT   = 50f;
    private static final float CONTENT_W    = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT;

    // Font sizes
    private static final float SIZE_TITLE   = 16f;
    private static final float SIZE_HEADING = 13f;
    private static final float SIZE_BODY    = 10f;
    private static final float SIZE_SMALL   =  8f;

    // Line spacing multipliers
    private static final float LEAD_TITLE   = 22f;
    private static final float LEAD_HEADING = 18f;
    private static final float LEAD_BODY    = 14f;

    private final ConfigLoader configLoader;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates a PDF for the given {@link QuestionPaper} and saves it to the output directory.
     *
     * @param paper the question paper to render
     * @return absolute path of the saved PDF file
     * @throws IOException if PDF creation or file writing fails
     */
    public Path generatePdf(QuestionPaper paper) throws IOException {
        Path outputDir = Paths.get(configLoader.getOutputDir());
        Files.createDirectories(outputDir);

        String filename = buildFilename(paper);
        Path   outPath  = outputDir.resolve(filename);

        try (PDDocument doc = new PDDocument()) {
            PDFont fontRegular  = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont fontBold     = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDFont fontMono     = new PDType1Font(Standard14Fonts.FontName.COURIER);
            PDFont fontHindi    = loadFont(doc, "fonts/NotoSansDevanagari-Regular.ttf", "Devanagari").orElse(fontRegular);
            // NotoSans covers Latin, Greek, ₹ and other Unicode used in question text
            PDFont fontNotoLatin = loadFont(doc, "fonts/NotoSans-Regular.ttf", "NotoSans").orElse(fontRegular);

            PageWriter pw = new PageWriter(doc, fontNotoLatin, fontBold, fontHindi, fontMono);

            writeHeader(pw, paper, fontBold, fontRegular);
            writeInstructions(pw, fontBold, fontRegular);
            writeBilingualQuestions(pw, paper, fontBold, fontNotoLatin, fontHindi);
            writeAnswerKey(pw, paper, fontBold, fontMono);

            pw.close();
            doc.save(outPath.toFile());
        }

        log.info("PDF saved: {}", outPath.toAbsolutePath());
        return outPath;
    }

    // -------------------------------------------------------------------------
    // Header
    // -------------------------------------------------------------------------

    private void writeHeader(PageWriter pw, QuestionPaper paper,
                              PDFont bold, PDFont regular) throws IOException {
        String dateStr = LocalDate.now().format(PRINT_DATE_FMT);

        pw.drawHRule();
        pw.newLine(4);
        pw.writeCentered("RAJASTHAN STAFF SELECTION BOARD (RSSB)", bold, SIZE_TITLE);
        pw.newLine(LEAD_TITLE);
        pw.writeCentered("COMPETITIVE EXAMINATION — QUESTION PAPER", bold, SIZE_HEADING);
        pw.newLine(LEAD_HEADING);
        pw.writeCentered("SET " + paper.getSetNumber()
                + "  |  " + paper.getCategory().name()
                + " LEVEL  |  " + dateStr, regular, SIZE_BODY);
        pw.newLine(LEAD_BODY + 4);
        pw.drawHRule();
        pw.newLine(LEAD_BODY);

        // Meta row
        pw.writeColumns(
                new String[]{"Total Questions: 100", "Max Marks: 100",
                        "Time: 2 Hours", "Negative Marking: 1/3"},
                regular, SIZE_SMALL
        );
        pw.newLine(LEAD_BODY + 4);
        pw.drawHRule();
        pw.newLine(LEAD_HEADING);
    }

    // -------------------------------------------------------------------------
    // Instructions
    // -------------------------------------------------------------------------

    private void writeInstructions(PageWriter pw, PDFont bold, PDFont regular) throws IOException {
        pw.writeLine("GENERAL INSTRUCTIONS", bold, SIZE_HEADING);
        pw.newLine(LEAD_HEADING);

        String[] instructions = {
            "1. This paper contains 100 Multiple Choice Questions (MCQs).",
            "2. Each question carries 1 mark. There is negative marking of 1/3 mark for each wrong answer.",
            "3. Do NOT mark more than one option per question.",
            "4. Section A contains Junior Level questions (Q1–Q50). Section B contains Senior Level questions (Q51–Q100).",
            "5. Each question is printed in BILINGUAL format — English first, Hindi immediately below.",
            "6. Answer based on either English or Hindi version — both are identical in meaning.",
            "7. Use Blue/Black ballpoint pen only. Pencil is NOT allowed.",
            "8. Mobile phones and electronic devices are strictly prohibited.",
            "9. Rough work may be done on the last page of the question booklet.",
            "10. Submit the answer sheet before leaving the examination hall."
        };

        for (String instr : instructions) {
            pw.writeWrapped(instr, regular, SIZE_BODY, LEAD_BODY, 0);
            pw.newLine(4);
        }
        pw.newLine(LEAD_HEADING);
        pw.drawHRule();
        pw.newLine(LEAD_HEADING);
    }

    // -------------------------------------------------------------------------
    // Bilingual Question Section (EN + HIN paired per question)
    // -------------------------------------------------------------------------

    private void writeBilingualQuestions(PageWriter pw, QuestionPaper paper,
                                          PDFont bold, PDFont regular, PDFont hindi) throws IOException {
        List<Question> enQuestions  = paper.getQuestions().stream()
                .filter(q -> q.getLanguage() == Language.EN).collect(Collectors.toList());
        List<Question> hinQuestions = paper.getQuestions().stream()
                .filter(q -> q.getLanguage() == Language.HIN).collect(Collectors.toList());

        // Determine Junior/Senior split (first 50 = Junior, next 50 = Senior)
        int juniorCount = Math.min(50, enQuestions.size());

        // — Junior Section —
        pw.writeLine("SECTION A — JUNIOR LEVEL  (Q1–Q50)", bold, SIZE_HEADING);
        pw.newLine(LEAD_HEADING);
        writePairedBlock(pw, enQuestions, hinQuestions, 0, juniorCount, regular, hindi, bold);

        pw.newLine(LEAD_HEADING);
        pw.drawHRule();
        pw.newLine(LEAD_HEADING);

        // — Senior Section —
        pw.writeLine("SECTION B — SENIOR LEVEL  (Q51–Q100)", bold, SIZE_HEADING);
        pw.newLine(LEAD_HEADING);
        writePairedBlock(pw, enQuestions, hinQuestions, juniorCount, enQuestions.size(), regular, hindi, bold);

        pw.newLine(LEAD_HEADING);
        pw.drawHRule();
        pw.newLine(LEAD_HEADING);
    }

    /**
     * Renders questions from startIdx (inclusive) to endIdx (exclusive),
     * pairing each English question immediately with its Hindi translation.
     */
    private void writePairedBlock(PageWriter pw,
                                   List<Question> enList, List<Question> hinList,
                                   int startIdx, int endIdx,
                                   PDFont regular, PDFont hindi, PDFont bold) throws IOException {
        for (int i = startIdx; i < endIdx && i < enList.size(); i++) {
            int qNum = i + 1;

            // English — segmented to handle any non-ASCII symbols (e.g. ₹)
            String enText = "Q" + qNum + ". " + enList.get(i).getText();
            pw.writeSegmentedWrapped(enText, regular, hindi, SIZE_BODY, LEAD_BODY, 14f);

            // Hindi (paired immediately below) — uses segmented font for mixed Latin+Devanagari
            if (i < hinList.size()) {
                String hinText = "     " + hinList.get(i).getText();
                pw.writeSegmentedWrapped(hinText, regular, hindi, SIZE_BODY, LEAD_BODY, 14f);
            }

            pw.newLine(8);
        }
    }

    // -------------------------------------------------------------------------
    // Legacy section methods kept for reference — no longer called
    // -------------------------------------------------------------------------

    private void writeSectionA(PageWriter pw, QuestionPaper paper,
                                PDFont bold, PDFont regular) throws IOException {
        List<Question> juniorEn = paper.getQuestions().stream()
                .filter(q -> q.getLanguage() == Language.EN)
                .collect(Collectors.toList());
        pw.writeLine("SECTION A — JUNIOR LEVEL  (English)", bold, SIZE_HEADING);
        pw.newLine(LEAD_HEADING);
        writeQuestionBlock(pw, juniorEn, 1, regular, bold);
        pw.newLine(LEAD_HEADING);
        pw.drawHRule();
        pw.newLine(LEAD_HEADING);
    }

    private void writeSectionB(PageWriter pw, QuestionPaper paper,
                                PDFont bold, PDFont regular) throws IOException {
        List<Question> allEn = paper.getQuestions().stream()
                .filter(q -> q.getLanguage() == Language.EN)
                .collect(Collectors.toList());
        int midpoint = allEn.size() / 2;
        List<Question> seniorEn = allEn.subList(midpoint, allEn.size());
        pw.writeLine("SECTION B — SENIOR LEVEL  (English)", bold, SIZE_HEADING);
        pw.newLine(LEAD_HEADING);
        writeQuestionBlock(pw, seniorEn, midpoint + 1, regular, bold);
        pw.newLine(LEAD_HEADING);
        pw.drawHRule();
        pw.newLine(LEAD_HEADING);
    }

    private void writeSectionC(PageWriter pw, QuestionPaper paper,
                                PDFont bold, PDFont hindi) throws IOException {
        List<Question> hindiQs = paper.getQuestions().stream()
                .filter(q -> q.getLanguage() == Language.HIN)
                .collect(Collectors.toList());
        pw.writeLine("SECTION C — सभी प्रश्न हिंदी में  (Hindi Reference)", bold, SIZE_HEADING);
        pw.newLine(LEAD_HEADING);
        writeQuestionBlock(pw, hindiQs, 1, hindi, bold);
        pw.newLine(LEAD_HEADING);
        pw.drawHRule();
        pw.newLine(LEAD_HEADING);
    }

    // -------------------------------------------------------------------------
    // Answer Key
    // -------------------------------------------------------------------------

    private void writeAnswerKey(PageWriter pw, QuestionPaper paper,
                                 PDFont bold, PDFont mono) throws IOException {
        pw.writeLine("ANSWER KEY — SET " + paper.getSetNumber(), bold, SIZE_HEADING);
        pw.newLine(LEAD_HEADING);

        Map<Integer, String> ak = paper.getAnswerKey();
        if (ak.isEmpty()) {
            pw.writeLine("(Answer key not available)", mono, SIZE_BODY);
            return;
        }

        // Print in rows of 10
        List<Integer> keys = new ArrayList<>(ak.keySet());
        int rowSize = 10;
        for (int i = 0; i < keys.size(); i += rowSize) {
            StringBuilder row = new StringBuilder();
            for (int j = i; j < Math.min(i + rowSize, keys.size()); j++) {
                int    qn  = keys.get(j);
                String ans = ak.get(qn).toUpperCase();
                row.append(String.format("Q%-3d(%s)   ", qn, ans));
            }
            pw.writeLine(row.toString().trim(), mono, SIZE_SMALL);
            pw.newLine(LEAD_BODY);
        }
        pw.newLine(LEAD_HEADING);
        pw.drawHRule();
    }

    // -------------------------------------------------------------------------
    // Shared question block renderer
    // -------------------------------------------------------------------------

    private void writeQuestionBlock(PageWriter pw, List<Question> questions,
                                     int startNum, PDFont regular, PDFont bold) throws IOException {
        int num = startNum;
        for (Question q : questions) {
            String qText = "Q" + num + ". " + q.getText();
            pw.writeWrapped(qText, regular, SIZE_BODY, LEAD_BODY, 12f);  // 12f indent for wrap
            pw.newLine(6);
            num++;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String buildFilename(QuestionPaper paper) {
        return String.format("Set%d_%s_%s.pdf",
                paper.getSetNumber(),
                capitalize(paper.getCategory().name()),
                LocalDate.now().format(FILE_DATE_FMT));
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.charAt(0) + s.substring(1).toLowerCase();
    }

    /**
     * Loads a TrueType font from the classpath.
     */
    private java.util.Optional<PDFont> loadFont(PDDocument doc, String classpathPath, String label) {
        try {
            ClassPathResource res = new ClassPathResource(classpathPath);
            if (res.exists()) {
                try (InputStream is = res.getInputStream()) {
                    PDFont font = PDType0Font.load(doc, is, true);
                    log.info("Loaded {} font from classpath: {}", label, classpathPath);
                    return java.util.Optional.of(font);
                }
            }
        } catch (Exception e) {
            log.warn("{} font not found or failed to load ({}): {}", label, classpathPath, e.getMessage());
        }
        return java.util.Optional.empty();
    }

    // =========================================================================
    // Inner class: PageWriter — manages pages, cursor position, and text ops
    // =========================================================================

    /**
     * Stateful helper that wraps PDPageContentStream and handles:
     * <ul>
     *   <li>Automatic page overflow and new-page creation</li>
     *   <li>Text wrapping within content width</li>
     *   <li>Horizontal rules, centered text, multi-column rows</li>
     * </ul>
     */
    private static final class PageWriter {

        private final PDDocument doc;
        private final PDFont     defaultRegular;
        private final PDFont     defaultBold;
        private final PDFont     defaultHindi;
        private final PDFont     defaultMono;

        private PDPage              currentPage;
        private PDPageContentStream cs;
        private float               cursorY;

        PageWriter(PDDocument doc, PDFont regular, PDFont bold,
                   PDFont hindi, PDFont mono) throws IOException {
            this.doc            = doc;
            this.defaultRegular = regular;
            this.defaultBold    = bold;
            this.defaultHindi   = hindi;
            this.defaultMono    = mono;
            newPage();
        }

        // ── Page management ───────────────────────────────────────────────────

        private void newPage() throws IOException {
            if (cs != null) cs.close();
            currentPage = new PDPage(PDRectangle.A4);
            doc.addPage(currentPage);
            cs      = new PDPageContentStream(doc, currentPage);
            cursorY = PAGE_HEIGHT - MARGIN_TOP;
        }

        private void ensureSpace(float needed) throws IOException {
            if (cursorY - needed < MARGIN_BOT) newPage();
        }

        void close() throws IOException {
            if (cs != null) cs.close();
        }

        // ── Cursor ────────────────────────────────────────────────────────────

        void newLine(float amount) throws IOException {
            cursorY -= amount;
            if (cursorY < MARGIN_BOT) newPage();
        }

        // ── Simple line ───────────────────────────────────────────────────────

        void writeLine(String text, PDFont font, float size) throws IOException {
            ensureSpace(size + 4);
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(MARGIN_LEFT, cursorY);
            cs.showText(sanitise(text));
            cs.endText();
            cursorY -= (size + 4);
        }

        // ── Centered line ─────────────────────────────────────────────────────

        void writeCentered(String text, PDFont font, float size) throws IOException {
            ensureSpace(size + 4);
            String safe = sanitise(text);
            float tw = safeStringWidth(font, safe, size);
            float x  = MARGIN_LEFT + (CONTENT_W - tw) / 2f;
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, cursorY);
            cs.showText(safe);
            cs.endText();
            cursorY -= (size + 4);
        }

        // ── Word-wrapped text (single font) ──────────────────────────────────

        void writeWrapped(String text, PDFont font, float size,
                          float leading, float wrapIndent) throws IOException {
            if (text == null || text.isBlank()) return;
            List<String> lines = wrap(sanitise(text), font, size, CONTENT_W, wrapIndent);
            boolean first = true;
            for (String line : lines) {
                ensureSpace(size + 2);
                float xOff = first ? MARGIN_LEFT : MARGIN_LEFT + wrapIndent;
                cs.beginText();
                cs.setFont(font, size);
                cs.newLineAtOffset(xOff, cursorY);
                cs.showText(line);
                cs.endText();
                cursorY -= leading;
                first = false;
            }
        }

        // ── Word-wrapped text with segmented font (Latin + Devanagari) ───────

        void writeSegmentedWrapped(String text, PDFont latinFont, PDFont devaFont,
                                   float size, float leading, float wrapIndent) throws IOException {
            if (text == null || text.isBlank()) return;
            List<String> lines = wrapSegmented(sanitise(text), latinFont, devaFont, size,
                                               CONTENT_W, wrapIndent);
            boolean first = true;
            for (String line : lines) {
                ensureSpace(size + 2);
                float xOff = first ? MARGIN_LEFT : MARGIN_LEFT + wrapIndent;
                renderSegmentedLine(line, latinFont, devaFont, size, xOff);
                cursorY -= leading;
                first = false;
            }
        }

        /** Renders one line with font switching between Latin and Devanagari runs. */
        private void renderSegmentedLine(String line, PDFont latinFont, PDFont devaFont,
                                         float size, float startX) throws IOException {
            float x = startX;
            StringBuilder run = new StringBuilder();
            boolean runNeedsDevaFont = false;
            boolean started = false;

            for (int i = 0; i < line.length(); ) {
                int     cp           = line.codePointAt(i);
                boolean needsDevaFont = cp >= 0x0900 && cp <= 0x097F; // Devanagari block only
                if (!started) { runNeedsDevaFont = needsDevaFont; started = true; }

                if (needsDevaFont != runNeedsDevaFont && run.length() > 0) {
                    PDFont f = runNeedsDevaFont ? devaFont : latinFont;
                    cs.beginText(); cs.setFont(f, size); cs.newLineAtOffset(x, cursorY);
                    cs.showText(run.toString()); cs.endText();
                    x += safeStringWidth(f, run.toString(), size);
                    run.setLength(0);
                    runNeedsDevaFont = needsDevaFont;
                }
                run.appendCodePoint(cp);
                i += Character.charCount(cp);
            }
            if (run.length() > 0) {
                PDFont f = runNeedsDevaFont ? devaFont : latinFont;
                cs.beginText(); cs.setFont(f, size); cs.newLineAtOffset(x, cursorY);
                cs.showText(run.toString()); cs.endText();
            }
        }

        /** Wraps mixed-script text using per-segment width calculation. */
        private List<String> wrapSegmented(String text, PDFont latinFont, PDFont devaFont,
                                           float size, float maxWidth, float indent) {
            List<String> result = new ArrayList<>();
            String[]      words  = text.split(" ");
            StringBuilder line   = new StringBuilder();
            float         curMax = maxWidth;

            for (String word : words) {
                String test  = line.isEmpty() ? word : line + " " + word;
                float  testW = segmentedWidth(test, latinFont, devaFont, size);
                if (testW > curMax && !line.isEmpty()) {
                    result.add(line.toString());
                    line   = new StringBuilder(word);
                    curMax = maxWidth - indent;
                } else {
                    if (!line.isEmpty()) line.append(" ");
                    line.append(word);
                }
            }
            if (!line.isEmpty()) result.add(line.toString());
            return result;
        }

        /** Measures text width using the correct font per character script. */
        private float segmentedWidth(String text, PDFont latinFont, PDFont devaFont, float size) {
            float         width = 0;
            StringBuilder run   = new StringBuilder();
            boolean       runNeedsDevaFont = false;
            boolean       started = false;

            for (int i = 0; i < text.length(); ) {
                int     cp            = text.codePointAt(i);
                boolean needsDevaFont = cp >= 0x0900 && cp <= 0x097F;
                if (!started) { runNeedsDevaFont = needsDevaFont; started = true; }

                if (needsDevaFont != runNeedsDevaFont && run.length() > 0) {
                    width += safeStringWidth(runNeedsDevaFont ? devaFont : latinFont, run.toString(), size);
                    run.setLength(0);
                    runNeedsDevaFont = needsDevaFont;
                }
                run.appendCodePoint(cp);
                i += Character.charCount(cp);
            }
            if (run.length() > 0)
                width += safeStringWidth(runNeedsDevaFont ? devaFont : latinFont, run.toString(), size);
            return width;
        }

        // ── Horizontal rule ───────────────────────────────────────────────────

        void drawHRule() throws IOException {
            ensureSpace(4);
            cs.setLineWidth(0.5f);
            cs.moveTo(MARGIN_LEFT, cursorY);
            cs.lineTo(PAGE_WIDTH - MARGIN_RIGHT, cursorY);
            cs.stroke();
            cursorY -= 4;
        }

        // ── Column row (equal-width cells) ────────────────────────────────────

        void writeColumns(String[] cells, PDFont font, float size) throws IOException {
            ensureSpace(size + 4);
            float colW = CONTENT_W / cells.length;
            cs.beginText();
            cs.setFont(font, size);
            for (int i = 0; i < cells.length; i++) {
                cs.newLineAtOffset(i == 0 ? MARGIN_LEFT : colW, 0);
                cs.showText(sanitise(cells[i]));
            }
            cs.endText();
            cursorY -= (size + 4);
        }

        // ── Word-wrap algorithm ───────────────────────────────────────────────

        private List<String> wrap(String text, PDFont font, float size,
                                  float maxWidth, float indentAfterFirst) throws IOException {
            List<String> result  = new ArrayList<>();
            String[]     words   = text.split(" ");
            StringBuilder line   = new StringBuilder();
            boolean       first  = true;
            float         curMax = maxWidth;

            for (String word : words) {
                String test  = line.isEmpty() ? word : line + " " + word;
                float  testW = safeStringWidth(font, test, size);
                if (testW > curMax && !line.isEmpty()) {
                    result.add(line.toString());
                    line    = new StringBuilder(word);
                    first   = false;
                    curMax  = maxWidth - indentAfterFirst;
                } else {
                    if (!line.isEmpty()) line.append(" ");
                    line.append(word);
                }
            }
            if (!line.isEmpty()) result.add(line.toString());
            return result;
        }

        /** Width calculation that falls back to an estimated width if font lacks a glyph. */
        private float safeStringWidth(PDFont font, String text, float size) {
            try {
                return font.getStringWidth(text) / 1000 * size;
            } catch (Exception e) {
                // Fallback: estimate ~0.6 * size per character
                return text.length() * size * 0.6f;
            }
        }

        private String sanitise(String s) {
            if (s == null) return "";
            // Allow printable ASCII and Devanagari Unicode block (U+0900–U+097F)
            // Replace only truly unprintable/control characters
            return s.chars()
                    .mapToObj(c -> {
                        if (c >= 32 && c < 127) return String.valueOf((char) c);       // ASCII printable
                        if (c >= 0x0900 && c <= 0x097F) return String.valueOf((char) c); // Devanagari
                        if (c >= 0x0020) return String.valueOf((char) c);              // other Unicode printable
                        return "";                                                       // strip control chars
                    })
                    .collect(Collectors.joining());
        }
    }
}
