/**
 * Internal parser for bcl2fastq and LRM Illumina samplesheets. Not
 * part of the public API; called from {@link SamplesheetReshape}.
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

    /**
     * Parse an Illumina samplesheet file. Auto-detects bcl2fastq
     * (has a [Data] section) vs Local Run Manager (header line at row 0).
     *
     * @param file the samplesheet
     * @return list of sample records as ordered maps
     * @throws IllegalArgumentException if the file is missing, empty,
     *     structurally malformed (bcl2fastq without [Data], duplicate
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

        // If the file has any bcl2fastq section markers, it must have
        // a [Data] section too. Without this check, a bcl2fastq file
        // missing [Data] would silently fall into the LRM path and
        // produce a confusing header-only (or wrong) output.
        boolean hasBcl2fastqMarker = lines.any { Line l ->
            l.content.trim() in ['[Header]', '[Reads]', '[Manifests]']
        }
        boolean hasDataMarker = lines.any { Line l ->
            l.content.trim() == '[Data]'
        }
        if (hasBcl2fastqMarker && !hasDataMarker) {
            throw new IllegalArgumentException(
                "Illumina samplesheet has bcl2fastq section markers but " +
                "no [Data] section: ${file.absolutePath}"
            )
        }

        int dataIdx = lines.findIndexOf { Line l -> l.content.trim() == '[Data]' }
        if (dataIdx >= 0) {
            if (dataIdx + 1 >= lines.size()) {
                return []
            }
            return parseTabular(
                parseCsvLine(lines[dataIdx + 1].content).collect { it.trim() },
                lines.drop(dataIdx + 2).collect { it.content },
                '[Data] section',
                lines[dataIdx + 1].lineNumber,
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
