/**
 * Internal validator for bcl2fastq structural checks. Not part of the
 * public API; called from {@link SamplesheetReshape}.
 */
class SamplesheetValidator {

    /**
     * Run the bcl2fastq-specific structural checks on an already-parsed
     * samplesheet: unique Sample_IDs, non-empty I7/I5 index values,
     * valid index sequences (A/C/G/T/N only), consistent I7/I5 lengths
     * across samples, and unique I7+I5 index combinations. All
     * detected issues are collected and reported in a single exception
     * so the user sees the complete list.
     *
     * The index checks only run if the corresponding column exists in
     * the samplesheet — LRM samplesheets (no index columns) pass the
     * index checks vacuously and only get the Sample_ID uniqueness
     * check.
     */
    static void validateBcl2fastqChecks(List<Map<String, String>> samples, File source) {
        List<String> errors = []
        Set<String> seenSampleIds = new HashSet<>()

        // Determine which index columns exist. If neither exists (LRM
        // samplesheet), the index-specific checks are skipped.
        boolean hasI7 = samples.any { it.containsKey('index') }
        boolean hasI5 = samples.any { it.containsKey('index2') }
        Map<String, List<String>> indexToSamples = new HashMap<>()

        // Track the first non-empty length seen per index column so we
        // can enforce consistent lengths across samples (bcl2fastq
        // requirement — a run with mixed 6bp and 8bp I7s is not
        // demultiplexable).
        Integer firstI7Length = null
        Integer firstI5Length = null

        samples.each { Map<String, String> sample ->
            String sampleId = sample.Sample_ID ?: sample.sample_id
            if (sampleId) {
                if (!seenSampleIds.add(sampleId)) {
                    errors << "Duplicate Sample_ID: '${sampleId}'"
                }
            }

            // I7 (`index`) — must be non-empty, only A/C/G/T/N, and
            // the same length as every other sample's I7.
            if (hasI7) {
                String i7 = sample.index
                if (i7?.trim()) {
                    String i7Trimmed = i7.trim()
                    if (firstI7Length == null) {
                        firstI7Length = i7Trimmed.length()
                    } else if (i7Trimmed.length() != firstI7Length) {
                        errors << "Sample '${sampleId}': I7 (index) length ${i7Trimmed.length()} differs from expected ${firstI7Length}"
                    }
                    String err = validateIndexSequence(i7, 'I7 (index)')
                    if (err) errors << "Sample '${sampleId}': ${err}"
                } else {
                    // The samplesheet has the index column but this
                    // sample has no value — bcl2fastq would have
                    // nothing to demultiplex by.
                    errors << "Sample '${sampleId}': I7 (index) value is empty"
                }
            }
            // I5 (`index2`) — same rules.
            if (hasI5) {
                String i5 = sample.index2
                if (i5?.trim()) {
                    String i5Trimmed = i5.trim()
                    if (firstI5Length == null) {
                        firstI5Length = i5Trimmed.length()
                    } else if (i5Trimmed.length() != firstI5Length) {
                        errors << "Sample '${sampleId}': I5 (index2) length ${i5Trimmed.length()} differs from expected ${firstI5Length}"
                    }
                    String err = validateIndexSequence(i5, 'I5 (index2)')
                    if (err) errors << "Sample '${sampleId}': ${err}"
                } else {
                    errors << "Sample '${sampleId}': I5 (index2) value is empty"
                }
            }

            // I7+I5 combination uniqueness (demultiplexing-critical).
            // Two samples with the same I7 AND the same I5 cannot be
            // distinguished by bcl2fastq; two samples with the same
            // I7 but different I5 are fine (dual-indexed).
            if (hasI7 || hasI5) {
                String key = (sample.index ?: '') + '|' + (sample.index2 ?: '')
                indexToSamples.computeIfAbsent(key, { k -> [] }).add(sampleId)
            }
        }

        indexToSamples.each { String key, List<String> sampleList ->
            if (sampleList.size() > 1) {
                def parts = key.split('\\|', 2)
                errors << "Duplicate index combination (I7='${parts[0]}', I5='${parts[1]}') used by samples: ${sampleList.join(', ')}"
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                "Illumina samplesheet has bcl2fastq validation issues (file: ${source?.absolutePath}):\n  " +
                errors.join('\n  ')
            )
        }
    }

    /**
     * Check that a sequence is a valid Illumina index: only A, C, G, T,
     * N (case-insensitive, normalised to uppercase). Returns an error
     * message string, or {@code null} if the sequence is valid.
     */
    private static String validateIndexSequence(String seq, String name) {
        String trimmed = seq.toUpperCase().trim()
        if (!trimmed.matches(/^[ACGTN]+$/)) {
            return "${name} contains invalid characters: '${seq}' (only A, C, G, T, N allowed)"
        }
        return null
    }
}
