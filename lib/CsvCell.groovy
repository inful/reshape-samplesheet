/**
 * RFC-4180 cell-level operations: quoting on the way out, parsing on
 * the way in. Symmetric counterpart to {@link CsvLines}, which owns
 * the LINE-level split (a {@code \n} inside a quoted cell is content,
 * not a line terminator). Together, {@link CsvLines} and
 * {@link CsvCell} own the full RFC-4180 contract for the lib's CSV
 * surfaces.
 *
 * <h2>Why a dedicated helper exists</h2>
 * Two parsers in the lib both parsed RFC-4180 cells (the input
 * samplesheet reader in {@link SamplesheetParser} and the lib's own
 * output reader in {@link ReshapedCsvParser}), and the writer in
 * {@link SamplesheetReshaper} quoted multi-value cells via an ad-hoc
 * {@code joinPaths} helper. None of those three knew about the other
 * two — bugs found in one weren't reported in the other. The
 * 0.3.0 release fixed RFC-4180 issues in two places; this class
 * makes future drift impossible by giving all three sites one set
 * of RFC-4180 primitives.
 *
 * <h2>What lives here</h2>
 * <ul>
 *   <li>{@link #quote} — wrap a value in {@code "…"} with embedded
 *       {@code "} doubled to {@code ""}, only when the value contains
 *       a character that requires quoting (the separator {@code ,},
 *       the quote itself, {@code \n}, or {@code \r}). Otherwise
 *       returns the value verbatim, including any whitespace the
 *       caller chose to preserve.</li>
 *   <li>{@link #parseLine} — parse one logical CSV line (already
 *       split, typically via {@link CsvLines#splitLogicalLines})
 *       into a list of cells, per RFC-4180. The only error it
 *       throws is for an unclosed quote at end of line.</li>
 * </ul>
 *
 * <h2>Round-trip property</h2>
 * For any cell value {@code s}, {@code parseLine(quote(s))[0] == s}
 * (when the writer's caller uses the parser's default of no
 * trimming). This is the invariant that lets the writer-side fix
 * for sample IDs with {@code ,} propagate cleanly to the
 * {@code samples} emit and the {@code parseReshapedCsv} public API.
 */
class CsvCell {

    /**
     * Quote a single cell value per RFC-4180.
     *
     * <p>Returns the input unchanged when it contains none of the
     * special characters that require quoting. Otherwise wraps the
     * value in {@code "…"} with any embedded {@code "} doubled to
     * {@code ""}. A {@code null} input is treated as the empty
     * string.</p>
     *
     * <p>The asymmetric difference from the older {@code joinPaths}
     * helper this replaces: the new rule also quotes (and escapes)
     * values containing {@code "}, {@code \n}, or {@code \r}, even
     * if they don't contain the separator. Previously a
     * sample-id-like value with an embedded {@code "} would have been
     * emitted as an unquoted literal — silently mis-aligned when
     * round-tripped through the parser.</p>
     *
     * @param cell the value to quote; may be {@code null}
     * @return the value, quoted if and only if it needs to be
     */
    static String quote(String cell) {
        if (cell == null) return ''
        if (needsQuoting(cell)) {
            return '"' + cell.replace('"', '""') + '"'
        }
        return cell
    }

    /**
     * Decide whether the given cell value requires wrapping in
     * double quotes per RFC-4180. A value needs quoting when it
     * contains the separator (the caller decides which character is
     * the separator — this helper doesn't know, so we use {@code ,}
     * as the canonical CSV separator and assume the caller uses the
     * same), the quote character, a line feed, or a carriage return.
     */
    private static boolean needsQuoting(String cell) {
        return cell.contains(',') ||
               cell.contains('"') ||
               cell.contains('\n') ||
               cell.contains('\r')
    }

    /**
     * Parse one logical CSV line into a list of cells, per
     * RFC-4180.
     *
     * <p>Rules (matching the spec):</p>
     * <ul>
     *   <li>A {@code "} at the <em>start</em> of a cell opens a
     *       quoted region; a {@code "} that appears anywhere else in
     *       an unquoted region is treated as a literal character
     *       (recovers gracefully from a malformed file rather than
     *       throwing on the first stray quote).</li>
     *   <li>A {@code ,} inside a quoted region is literal content
     *       (this is the entire reason quotes exist in CSV at all).</li>
     *   <li>A doubled {@code ""} inside a quoted region becomes a
     *       literal {@code "}.</li>
     *   <li>Trailing empty cells are preserved: a row ending in
     *       {@code ,} produces one extra empty cell (this is what
     *       makes the single-end case — empty {@code fastq_2} —
     *       round-trippable through {@link ReshapedCsvParser}).</li>
     * </ul>
     *
     * @param line       the line text (without trailing newline)
     * @param lineNumber 1-based source line number, included in the
     *                   error message when an unclosed quote is
     *                   detected. Pass {@code -1} (the default) to
     *                   suppress the line-number reference — useful
     *                   for lines that don't have a meaningful
     *                   source position (e.g. a synthetic header).
     * @return the cells in document order; always at least one
     *         element (an empty line parses to a single empty cell
     *         — callers that want to drop empty lines should filter
     *         beforehand)
     * @throws IllegalArgumentException if {@code line} ends inside
     *         an open quoted region (i.e. an unclosed quote)
     */
    static List<String> parseLine(String line, int lineNumber = -1) {
        List<String> cells = []
        StringBuilder current = new StringBuilder()
        boolean inQuotes = false
        boolean atCellStart = true
        int i = 0
        while (i < line.length()) {
            char c = line.charAt(i)
            if (inQuotes) {
                if (c == '"') {
                    // Doubled "" inside a quoted region is a literal
                    // "; a single " closes the region.
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"')
                        i += 2
                        continue
                    }
                    inQuotes = false
                    i += 1
                    continue
                }
                current.append(c)
                i += 1
            } else {
                if (c == ',') {
                    cells << current.toString()
                    current = new StringBuilder()
                    atCellStart = true
                    i += 1
                } else if (c == '"' && atCellStart) {
                    inQuotes = true
                    atCellStart = false
                    i += 1
                } else {
                    current.append(c)
                    atCellStart = false
                    i += 1
                }
            }
        }
        if (inQuotes) {
            String location = lineNumber >= 0
                ? "CSV line ${lineNumber}"
                : "CSV line"
            throw new IllegalArgumentException(
                "Unclosed quote in ${location}: ${line}"
            )
        }
        cells << current.toString()
        return cells
    }
}
