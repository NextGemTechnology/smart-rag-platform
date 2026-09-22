package com.nextgem.smartrag.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-performance structural Markdown parser for Apache PDFBox.
 * Extracts text page-by-page and streams GitHub-flavored Markdown directly
 * into an output writer with zero accumulation of global state.
 */
public class MarkdownStructuralPdfStripper {

    private static final Pattern LEGAL_SECTION_PATTERN = Pattern.compile("^(\\d+(\\.\\d+)+)\\s+(.+)$");
    private static final Pattern NUMBERED_LIST_PATTERN = Pattern.compile("^(\\d+\\.)\\s+(.+)$");
    private static final Pattern BULLET_PATTERN = Pattern.compile("^[•\\-*–—]\\s+(.+)$");
    private static final Pattern MULTI_SPACE_DELIMITER = Pattern.compile("\\s{3,}");

    private final PDFTextStripper textStripper;

    public MarkdownStructuralPdfStripper() throws IOException {
        this.textStripper = new PDFTextStripper();
        this.textStripper.setSortByPosition(true);
        this.textStripper.setShouldSeparateByBeads(false);
    }

    /**
     * Streams an individual page directly to the BufferedWriter, preserving layout.
     * Ensures minimum memory residency by processing and flushing line-by-line.
     */
    public void streamPageAsMarkdown(PDDocument document, int pageNumber, BufferedWriter writer) throws IOException {
        textStripper.setStartPage(pageNumber);
        textStripper.setEndPage(pageNumber);

        String rawPageText = textStripper.getText(document);

        writer.write("## Page " + pageNumber);
        writer.newLine();
        writer.newLine();

        if (rawPageText == null || rawPageText.trim().isEmpty()) {
            writer.write("*(blank page)*");
            writer.newLine();
            writer.newLine();
            writer.flush();
            return;
        }

        String[] lines = rawPageText.split("\\r?\\n");
        List<String[]> potentialTableRows = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (!potentialTableRows.isEmpty()) {
                    flushTable(potentialTableRows, writer);
                    potentialTableRows.clear();
                }
                writer.newLine();
                continue;
            }

            // Detect potential tabular row (3+ spaces or tabs separating tokens)
            String[] tokens = MULTI_SPACE_DELIMITER.split(trimmed);
            if (tokens.length >= 2 && trimmed.length() > 10) {
                potentialTableRows.add(tokens);
                continue;
            } else if (!potentialTableRows.isEmpty()) {
                flushTable(potentialTableRows, writer);
                potentialTableRows.clear();
            }

            // Legal Numbering Hierarchy (e.g., 3.7.1 Sub-clause or 1.2 Title)
            Matcher legalMatcher = LEGAL_SECTION_PATTERN.matcher(trimmed);
            if (legalMatcher.matches()) {
                writer.write("### " + legalMatcher.group(1) + " " + legalMatcher.group(3));
                writer.newLine();
                continue;
            }

            // Standard Numbered List (e.g., 1. First item)
            Matcher numberedMatcher = NUMBERED_LIST_PATTERN.matcher(trimmed);
            if (numberedMatcher.matches()) {
                writer.write(numberedMatcher.group(1) + " " + numberedMatcher.group(2));
                writer.newLine();
                continue;
            }

            // Bullet Point Normalization
            Matcher bulletMatcher = BULLET_PATTERN.matcher(trimmed);
            if (bulletMatcher.matches()) {
                writer.write("- " + bulletMatcher.group(1));
                writer.newLine();
                continue;
            }

            // Short all-caps or title headers (common in legal orders)
            if (isHeadingCandidate(trimmed)) {
                writer.write("### " + trimmed);
                writer.newLine();
                continue;
            }

            // Standard Paragraph Line
            writer.write(trimmed);
            writer.newLine();
        }

        // Flush any trailing table remaining at page end
        if (!potentialTableRows.isEmpty()) {
            flushTable(potentialTableRows, writer);
            potentialTableRows.clear();
        }

        writer.newLine();
        writer.flush();
    }

    private boolean isHeadingCandidate(String line) {
        if (line.length() < 5 || line.length() > 80) return false;
        if (line.endsWith(".") || line.endsWith(",") || line.endsWith(";")) return false;
        // Check if upper-case dominant or official keywords
        boolean isAllUpper = line.toUpperCase().equals(line) && line.matches(".*[A-Z].*");
        boolean isHeaderKeyword = line.startsWith("Order") || line.startsWith("Chapter") || 
                                  line.startsWith("Section") || line.startsWith("कार्यालय");
        return isAllUpper || isHeaderKeyword;
    }

    private void flushTable(List<String[]> rows, BufferedWriter writer) throws IOException {
        if (rows.isEmpty()) return;
        
        // Find max column count
        int maxCols = 0;
        for (String[] r : rows) {
            maxCols = Math.max(maxCols, r.length);
        }
        if (maxCols < 2) {
            for (String[] r : rows) {
                writer.write(String.join(" ", r));
                writer.newLine();
            }
            return;
        }

        // Header Row
        String[] header = rows.get(0);
        writer.write("|");
        for (int i = 0; i < maxCols; i++) {
            writer.write(" " + (i < header.length ? cleanCell(header[i]) : "Col " + (i + 1)) + " |");
        }
        writer.newLine();

        // Separator Row
        writer.write("|");
        for (int i = 0; i < maxCols; i++) {
            writer.write("---|");
        }
        writer.newLine();

        // Data Rows
        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            writer.write("|");
            for (int c = 0; c < maxCols; c++) {
                writer.write(" " + (c < row.length ? cleanCell(row[c]) : "") + " |");
            }
            writer.newLine();
        }
        writer.newLine();
    }

    private String cleanCell(String content) {
        return content.replace("|", "\\|").trim();
    }
}
