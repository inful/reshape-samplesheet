/**
 * Internal parser for bcl2fastq (V1), BCLConvert (V2), and Local Run
 * Manager Illumina samplesheets. Not part of the public API; called
 * from {@link SamplesheetReshape}.
 */
class SamplesheetParser {

    /**
     * One logical line of the samplesheet, paired with its 1-based
     * source line number for use in error messages and warnings.
     */
    static class Line {
        final String content
        final int lineNumber
        Line(String content, int lineNumber) {
            this.content = content
            this.lineNumber = lineNumber
        }
    }

    // V1 (bcl2fastq / IEM) section markers.
    private static final Set<String> V1_SECTION_MARKERS =
        ['[Header]', '[Reads]', '[Manifests]'] as Set
    private static final String V1_DATA_MARKER = '[Data]'

    // V2 (BCLConvert) section markers. NovaSeq X series and
    // newer Illumina instruments emit BCLConvert by default. The
    // cloud-based variant uses [Cloud_Data] instead.
    private static final Set<String> V2_SECTION_MARKERS =
        ['[BCLConvert_Settings]', '[FileFormat]', '[RunInfo]'] as Set
    private static final Set<String> V2_DATA_MARKERS =
        ['[BCLConvert_Data]', '[Cloud_Data]'] as Set
    private static final String V2_FILE_FORMAT_VERSION_HEADER = 'FileFormatVersion'

    /**
     * Parse an Illumina samplesheet file. Auto-detects the format:
     * <ul>
     *   <li><b>V1 (bcl2fastq / IEM)</b> — has a {@code [Data]} section.
     *       Common on NovaSeq 6000 and earlier Illumina platforms.</li>
     *   <li><b>V2 (BCLConvert)</b> — has a {@code [BCLConvert_Data]} or
     *       {@code [Cloud_Data]} section. Common on NovaSeq X series and
     *       newer Illumina platforms. V2-specific fields like
     *       {@code [BCLConvert_Settings]} and {@code OverrideCycles} are
     *       recognised (so a malformed V2 samplesheet fails clearly) but
     *       are not used by the reshape use case — the per-sample data
     *       rows have the same structure as V1.</li>
     *   <li><b>LRM (Local Run Manager)</b> — no section markers; the
     *       first line is the header and subsequent lines are data rows.</li>
     * </ul>
     *
     * @param file the samplesheet
     * @return list of sample records as ordered maps
     * @throws IllegalArgumentException if the file is missing, empty,
     *     structurally malformed (missing data section, duplicate
     *     header columns, empty header cell, missing Sample_ID column),
     *     or has malformed rows (wrong cell count, empty Sample_ID value)
     */
    static List<Map<String, String>> parseSamplesheetImpl(File file) {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException(
                "Illumina samplesheet not found: ${file?.absolutePath}"
            )
        }
        // Read explicitly as UTF-8. The default `file.text` uses the
        // JVM's platform default encoding, which varies by environment
        // and silently mangles non-ASCII sample names.
        String text = file.getText('UTF-8')
        // Strip UTF-8 BOM if present
        if (text.length() > 0 && (text.charAt(0) as int) == 0xFEFF) {
            text = text.substring(1)
        }
        // Split into logical lines, respecting newlines inside quoted
        // CSV fields. The current line's 1-based source line number is
        // preserved for use in error messages and warnings.
        List<Line> allLines = splitLogicalLines(text)
        List<Line> lines = allLines.findAll { !it.content.trim().isEmpty() }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException(
                "Illumina samplesheet is empty: ${file.absolutePath}"
            )
        }

        // V2 takes precedence over V1 if both are present (a malformed
        // file would have both, but the V2 data section is the one
        // BCLConvert reads).
        int v2DataIdx = findSectionIdx(lines, V2_DATA_MARKERS)
        int v1DataIdx = findSectionIdx(lines, [V1_DATA_MARKER] as Set)

        if (v2DataIdx >= 0) {
            return parseV2(lines, v2DataIdx, file)
        }

        // If the file has any bcl2fastq section markers but no
        // [Data] section, it must have a [Data] section to be
        // valid — without this check, a bcl2fastq file missing
        // [Data] would silently fall into the LRM path and
        // produce a confusing header-only (or wrong) output.
        boolean hasV1Marker = lines.any { Line l ->
            V1_SECTION_MARKERS.contains(l.content.trim())
        }
        if (hasV1Marker && v1DataIdx < 0) {
            throw new IllegalArgumentException(
                "Illumina samplesheet has bcl2fastq section markers but " +
                "no [Data] section: ${file.absolutePath}"
            )
        }

        if (v1DataIdx >= 0) {
            if (v1DataIdx + 1 >= lines.size()) {
                return []
            }
            return parseTabular(
                parseCsvLine(lines[v1DataIdx + 1].content).collect { it.trim() },
                lines.drop(v1DataIdx + 2).collect { it.content },
                '[Data] section',
                lines[v1DataIdx + 1].lineNumber,
                file
            )
        }
        return parseTabular(
            parseCsvLine(lines[0].content).collect { it.trim() },
            lines.drop(1).collect { it.content },
            'header row',
            lines[0].lineNumber,
            file
        )
    }

    /**
     * Find the first line whose content is one of the given
     * section markers. Returns -1 if none found.
     */
    private static int findSectionIdx(List<Line> lines, Set<String> markers) {
        return lines.findIndexOf { Line l -> markers.contains(l.content.trim()) }
    }

    /**
     * Parse a V2 (BCLConvert) samplesheet. The data section is
     * structurally identical to V1's [Data] section, so the parsing
     * is the same — the only difference is the section name and
     * the presence of [BCLConvert_Settings] / [Header] sections
     * with V2-specific fields (FileFormatVersion, OverrideCycles,
     * etc.) that we don't use for the reshape use case.
     */
    private static List<Map<String, String>> parseV2(
        List<Line> lines,
        int dataIdx,
        File file
    ) {
        String dataMarker = lines[dataIdx].content.trim()
        if (dataIdx + 1 >= lines.size()) {
            return []
        }
        return parseTabular(
            parseCsvLine(lines[dataIdx + 1].content).collect { it.trim() },
            lines.drop(dataIdx + 2).collect { it.content },
            "${dataMarker} section",
            lines[dataIdx + 1].lineNumber,
            file
        )
    }

    /**
     * Build sample records from a header + data rows. Both bcl2fastq and
     * LRM samplesheets reduce to (header, rows) once the section markers
     * are stripped, so the per-row logic lives here.
     *
     * Collects all per-row problems and throws once with the complete
     * list, so the user can fix the whole samplesheet in one pass
     * rather than discovering issues one at a time.
     */
    private static List<Map<String, String>> parseTabular(
        List<String> header,
        List<String> rows,
        String context,
        int headerLineNumber,
        File source
    ) {
        if (header.isEmpty()) {
            throw new IllegalArgumentException(
                "Illumina samplesheet has no header row (${context}) in ${source?.absolutePath}"
            )
        }
        if (header.any { !it }) {
            int badIdx = header.findIndexOf { !it }
            throw new IllegalArgumentException(
                "Illumina samplesheet has an empty header cell at column " +
                "${badIdx + 1} in ${context} (file: ${source?.absolutePath})"
            )
        }
        // Reject duplicate column names. Last-write-wins would silently
        // produce wrong records for the loser column.
        Map<String, Integer> seen = [:]
        header.eachWithIndex { String col, int i ->
            if (seen.containsKey(col)) {
                throw new IllegalArgumentException(
                    "Illumina samplesheet has duplicate header column '${col}' " +
                    "at positions ${seen[col] + 1} and ${i + 1} in ${context} " +
                    "(file: ${source?.absolutePath})"
                )
            }
            seen[col] = i
        }
        // The Sample_ID column is required — without it, every record
        // would be silently dropped at reshape time. Fail at parse
        // time with a clear message instead.
        boolean hasSampleId = header.any { it.toLowerCase() == 'sample_id' }
        if (!hasSampleId) {
            throw new IllegalArgumentException(
                "Illumina samplesheet is missing a Sample_ID column in " +
                "${context} (found columns: ${header.join(', ')}, file: " +
                "${source?.absolutePath})"
            )
        }
        List<Map<String, String>> records = []
        List<String> rowErrors = []
        rows.eachWithIndex { String line, int i ->
            int lineNumber = headerLineNumber + 1 + i
            List<String> cells = parseCsvLine(line)
            if (cells.size() == header.size()) {
                Map<String, String> record = [:]
                header.eachWithIndex { String col, int j -> record[col] = cells[j].trim() }
                // A row that parses but has an empty Sample_ID is also
                // a real error: silently skipping it would mean the
                // downstream pipeline doesn't process that sample.
                String sampleId = record.Sample_ID ?: record.sample_id
                if (!sampleId) {
                    rowErrors << "line ${lineNumber}: sample with no Sample_ID value"
                    return
                }
                records << record
            } else if (cells.any { it.trim() }) {
                rowErrors << "line ${lineNumber}: ${cells.size()} cells, expected ${header.size()}"
            }
        }
        if (!rowErrors.isEmpty()) {
            throw new IllegalArgumentException(
                "Illumina samplesheet has ${rowErrors.size()} malformed row(s) in " +
                "${context} (file: ${source?.absolutePath}):\n  " +
                rowErrors.join('\n  ')
            )
        }
        return records
    }

    /**
     * Split the file content into logical lines, respecting newlines
     * inside double-quoted CSV fields. Returns each line paired with
     * its 1-based source line number so error messages can point to
     * the right place.
     */
    private static List<Line> splitLogicalLines(String text) {
        List<Line> result = []
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
                result << new Line(current.toString(), startLine)
                current = new StringBuilder()
                currentLine++
                startLine = currentLine
                i++
            } else if (!inQuotes && c == '\r') {
                result << new Line(current.toString(), startLine)
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
            result << new Line(current.toString(), startLine)
        }
        return result
    }

    /**
     * Minimal RFC-4180-ish CSV line parser:
     *  - supports double-quoted fields
     *  - supports escaped quotes ("") inside quoted fields
     *  - supports commas inside quoted fields
     *  - called per logical line (already split quote-aware)
     */
    private static List<String> parseCsvLine(String line) {
        List<String> cells = []
        StringBuilder current = new StringBuilder()
        boolean inQuotes = false
        int i = 0
        while (i < line.length()) {
            char c = line.charAt(i)
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"')
                        i += 2
                        continue
                    }
                    inQuotes = false
                    i++
                    continue
                }
                current.append(c)
                i++
            } else {
                if (c == ',') {
                    cells << current.toString()
                    current = new StringBuilder()
                    i++
                } else if (c == '"' && current.length() == 0) {
                    inQuotes = true
                    i++
                } else {
                    current.append(c)
                    i++
                }
            }
        }
        cells << current.toString()
        return cells
    }
}
