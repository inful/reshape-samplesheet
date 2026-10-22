/**
 * Shared utilities for parsing CSV-shaped text. Used by both
 * {@link SamplesheetParser} (which reads Illumina input samplesheets)
 * and {@link ReshapedCsvParser} (which reads the lib's own output).
 *
 * The previous design inlined this logic in SamplesheetParser,
 * but ReshapedCsvParser needed the same quote-aware line splitting
 * to honour RFC-4180 multi-line quoted cells. Extracting it here
 * keeps the splitting logic in one place and gives both parsers
 * access to source line numbers for error messages.
 *
 * Pairs with {@link CsvLine} for the per-line data class and
 * {@link CsvCell} for the cell-level quote/parse operations. The
 * three together own the full RFC-4180 contract for the lib.
 */
class CsvLines {

    /**
     * Split CSV text into logical lines, respecting newlines
     * inside double-quoted fields. A {@code \n} or {@code \r} that
     * appears inside a quoted region is treated as part of the
     * cell content, not a line terminator.
     *
     * Trailing empty content (from a file that ends with a
     * newline) is not emitted; an empty line in the middle of the
     * file IS emitted as a {@link CsvLine} with empty content.
     * Callers that want to drop empty lines can filter on
     * {@code content}.
     *
     * Line numbers are 1-based and refer to the original file
     * position, so error messages can point to the right place.
     *
     * @param text the full file contents
     * @return list of logical lines in document order
     */
    static List<CsvLine> splitLogicalLines(String text) {
        List<CsvLine> result = []
        StringBuilder current = new StringBuilder()
        int currentLine = 1
        int startLine = 1
        boolean inQuotes = false
        // A `"` only opens a quoted region at the START of a cell.
        // Without this, a stray `"` in the middle of an unquoted field
        // (a hand-edited samplesheet, an Excel export) put the splitter
        // into "inside quotes" mode and swallowed every following row
        // into one logical line — which then surfaced as a cell-count
        // error on the wrong line number. This mirrors the rule
        // CsvCell.parseLine already uses, so the two no longer disagree
        // about what a mid-cell quote means.
        boolean atCellStart = true
        int i = 0
        while (i < text.length()) {
            char c = text.charAt(i)
            if (c == '"') {
                if (inQuotes) {
                    // A doubled "" inside a quoted region is an escaped
                    // quote: stay in the region and keep both characters.
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        current.append(c).append('"')
                        i += 2
                        continue
                    }
                    inQuotes = false
                    current.append(c)
                    i++
                } else if (atCellStart) {
                    inQuotes = true
                    atCellStart = false
                    current.append(c)
                    i++
                } else {
                    // Mid-cell quote in unquoted content: literal.
                    current.append(c)
                    i++
                }
            } else if (!inQuotes && c == '\n') {
                result << new CsvLine(current.toString(), startLine)
                current = new StringBuilder()
                currentLine++
                startLine = currentLine
                atCellStart = true
                i++
            } else if (!inQuotes && c == '\r') {
                result << new CsvLine(current.toString(), startLine)
                current = new StringBuilder()
                // Swallow the LF half of a CRLF if present
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i += 2
                } else {
                    i++
                }
                currentLine++
                startLine = currentLine
                atCellStart = true
            } else {
                if (!inQuotes && c == ',') {
                    atCellStart = true
                } else {
                    atCellStart = false
                }
                current.append(c)
                i++
            }
        }
        if (current.length() > 0) {
            result << new CsvLine(current.toString(), startLine)
        }
        return result
    }
}
