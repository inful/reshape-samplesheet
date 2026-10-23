/**
 * RFC-4180 reader for the lib's own output ({@code writeReshaped}).
 *
 * Why a dedicated parser: the lib's emit CSV uses RFC-4180 quoted
 * multi-value cells (e.g. {@code "/path/lane1.fq,/path/lane2.fq"})
 * to keep the multi-lane aggregation in a single cell. Nextflow's
 * built-in {@code splitCsv(header: true)} does not honour
 * double-quoted cells — it splits on the inner {@code ,}, leaves
 * stray quote characters in the cell values, and drops columns
 * silently. So we need a small RFC-4180 reader that lives in the
 * lib, is unit-tested in isolation, and is the canonical
 * implementation that the sub-workflow and every downstream
 * consumer go through.
 *
 * <h2>Scope</h2>
 * This reader is specifically for the lib's OUTPUT format
 * (header: {@code sample,fastq_1,fastq_2[,strandedness]}). It is
 * NOT a general-purpose CSV reader — for the INPUT samplesheet
 * formats (bcl2fastq V1, BCLConvert V2, Local Run Manager), use
 * {@link SamplesheetParser}, which handles the
 * section-header-and-multi-section structure of those formats.
 *
 * <h2>RFC-4180 contract</h2>
 * The reader delegates cell-level parsing to {@link CsvCell}, which
 * is also used (and unit-tested in isolation) by
 * {@link SamplesheetParser} for input samplesheets. The two parsers
 * cannot drift because they share the same primitive.
 *
 * <h2>Handling</h2>
 * <ul>
 *   <li>Quoted fields with the separator inside</li>
 *   <li>Escaped double-quotes inside a quoted field ({@code ""} → {@code "})</li>
 *   <li>Multi-line quoted cells (a {@code \n} inside a quoted region
 *       is content, not a line terminator — full RFC-4180)</li>
 *   <li>Empty trailing fields (a single-end row's empty {@code fastq_2})</li>
 *   <li>UTF-8 BOM at start of file</li>
 *   <li>CRLF / LF / CR line endings</li>
 *   <li>Source line numbers in error messages so a user can find
 *       the offending line in the original CSV</li>
 * </ul>
 *
 * <h2>Output shape</h2>
 * Each record is a Map with three keys:
 * <ul>
 *   <li>{@code meta}    — {@code [id: String, single_end: boolean]},
 *       plus {@code strandedness: String} when the CSV has a
 *       non-empty {@code strandedness} column</li>
 *   <li>{@code fastq_1} — {@code [File, ...]} (possibly empty)</li>
 *   <li>{@code fastq_2} — {@code [File, ...]} (empty for single-end)</li>
 * </ul>
 */
class ReshapedCsvParser {

    /**
     * Parse a CSV file. See the class-level docs for the output shape.
     *
     * @throws IllegalArgumentException for unrecoverable malformation
     *         (unclosed quote, header missing {@code sample}, etc.)
     */
    static List<Map<String, Object>> parse(File file) {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException(
                "Cannot parse CSV: file does not exist: ${file?.absolutePath}"
            )
        }
        String text = new String(file.bytes, 'UTF-8')
        // Strip a UTF-8 BOM if present. The lib's writer never emits
        // one but external tools might, and we want the parser to
        // work on CSVs from anywhere.
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') {
            text = text.substring(1)
        }
        // Quote-aware line splitting — a `\n` inside a double-quoted
        // cell is treated as content, not a line terminator. This
        // matches RFC-4180 multi-line quoted cells; the previous
        // `text.split(/\r\n|\r|\n/)` would have silently broken
        // on a CSV that contains a multi-line cell. Source line
        // numbers (1-based) are preserved for error messages.
        List<CsvLine> allLines = CsvLines.splitLogicalLines(text)
        // Drop empty lines (both leading and trailing newlines and
        // blank lines in the middle). Empty content with a real
        // line number is still kept so the line number is
        // meaningful for error messages, but the parser skips it
        // when building records.
        List<CsvLine> lines = allLines.findAll { it.content.length() > 0 }
        if (lines.isEmpty()) {
            return []
        }

        // Parse every line into a list of cells, threading the
        // 1-based source line number into CsvCell.parseLine so the
        // unclosed-quote error can point to the right place.
        List<List<String>> rows = []
        List<Integer> rowLineNumbers = []
        lines.each { CsvLine lineInfo ->
            rows << CsvCell.parseLine(lineInfo.content, lineInfo.lineNumber)
            rowLineNumbers << lineInfo.lineNumber
        }

        // First row is the header. Must contain at least 'sample'.
        List<String> header = rows[0]
        int sampleIdx = header.indexOf('sample')
        if (sampleIdx < 0) {
            throw new IllegalArgumentException(
                "Reshaped CSV is missing the required 'sample' column header. " +
                "Found headers: ${header}"
            )
        }
        int fq1Idx = header.indexOf('fastq_1')
        int fq2Idx = header.indexOf('fastq_2')
        // The strandedness column is optional: the writer only emits it
        // when the caller asked for one.
        int strandedIdx = header.indexOf('strandedness')
        // fastq_1 and fastq_2 are technically optional in a generic
        // CSV, but our writer always emits them. A defensive parse
        // returns empty lists for missing columns.
        if (fq1Idx < 0) fq1Idx = -1
        if (fq2Idx < 0) fq2Idx = -1

        List<Map<String, Object>> records = []
        List<List<String>> dataRows = rows.drop(1)
        int dataRowCount = dataRows.size()
        for (int i = 0; i < dataRowCount; i++) {
            List<String> row = dataRows[i]
            // rowLineNumbers is aligned with `rows` (header + data
            // rows), so the i-th data row is at index i+1.
            int lineNumber = rowLineNumbers[i + 1]
            String sampleId = (sampleIdx < row.size() ? row[sampleIdx] : '').trim()
            def meta = [
                id         : sampleId,
                single_end : !(fq2Idx >= 0 && fq2Idx < row.size() && row[fq2Idx].trim())
            ]
            // Carry the strandedness column into meta. The `samples`
            // emit is built by re-parsing this CSV, so a column the
            // parser ignores is a column the consumer never sees —
            // the info was in the CSV but silently dropped on the way
            // out. Absent or empty means "no strandedness", so the key
            // is left out entirely rather than set to null.
            if (strandedIdx >= 0 && strandedIdx < row.size()) {
                String stranded = row[strandedIdx].trim()
                if (stranded) {
                    meta.strandedness = stranded
                }
            }
            // Split the cell on `,` to recover the per-lane file
            // list. The writer joins paths with `,` inside quoted
            // cells; after RFC-4180 parsing the cell is a single
            // string like "/path/lane1.fq,/path/lane2.fq".
            def fastq1 = []
            if (fq1Idx >= 0 && fq1Idx < row.size()) {
                String cell = row[fq1Idx].trim()
                if (cell) {
                    fastq1 = cell.split(',').collect { new File(it) }
                }
            }
            def fastq2 = []
            if (fq2Idx >= 0 && fq2Idx < row.size()) {
                String cell = row[fq2Idx].trim()
                if (cell) {
                    fastq2 = cell.split(',').collect { new File(it) }
                }
            }
            records << [
                meta   : meta,
                fastq_1: fastq1,
                fastq_2: fastq2
            ]
        }
        return records
    }
}
