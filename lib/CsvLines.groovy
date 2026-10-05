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
 * Pairs with {@link CsvLine} for the per-line data class.
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
        int i = 0
        while (i < text.length()) {
            char c = text.charAt(i)
            if (c == '"') {
                inQuotes = !inQuotes
                current.append(c)
                i++
            } else if (!inQuotes && c == '\n') {
                result << new CsvLine(current.toString(), startLine)
                current = new StringBuilder()
                currentLine++
                startLine = currentLine
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
            } else {
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
