/**
 * Internal validator for bcl2fastq structural checks. Not part of the
 * public API; called from {@link SamplesheetReshape}.
 */
class SamplesheetValidator {

    /**
     * Default minimum Hamming distance required between any two
     * indices in the same column. Illumina's demultiplexing
     * tolerance is roughly 1 substitution per index read, so
     * indices that differ by only 1 base can be cross-assigned
     * by a single sequencing error. The samplesheet-parser tool
     * (nf-core/samplesheetparser/validate) uses 2 as the default;
     * we match that.
     */
    static final int DEFAULT_MIN_HAMMING_DISTANCE = 2

    /**
     * Run the bcl2fastq-specific structural checks on an already-parsed
     * samplesheet: unique Sample_IDs, non-empty I7/I5 index values,
     * valid index sequences (A/C/G/T/N only), consistent I7/I5 lengths
     * across samples, unique I7+I5 index combinations, and minimum
     * Hamming distance between any two indices in the same column.
     * All detected issues are collected and reported in a single
     * exception so the user sees the complete list.
     *
     * The index checks only run if the corresponding column exists in
     * the samplesheet — LRM samplesheets (no index columns) pass the
     * index checks vacuously and only get the Sample_ID uniqueness
     * check.
     */
    static void validateBcl2fastqChecks(List<Map<String, String>> samples, File source) {
        validateBcl2fastqChecks(samples, source, DEFAULT_MIN_HAMMING_DISTANCE)
    }

    /**
     * Same as {@link #validateBcl2fastqChecks(List, File)} but with
     * an explicit minimum Hamming distance. A distance of 0 disables
     * the check (useful for tiny test fixtures that wouldn't
     * otherwise pass).
     */
    static void validateBcl2fastqChecks(List<Map<String, String>> samples, File source, int minHammingDistance) {
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

        // Hamming distance check — last so the more specific errors
        // (uniqueness, length, characters) are reported first.
        if (minHammingDistance > 0) {
            checkHammingDistances(samples, 'I7 (index)', 'index',  errors, minHammingDistance)
            checkHammingDistances(samples, 'I5 (index2)', 'index2', errors, minHammingDistance)
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

    /**
     * Compute the Hamming distance between two equal-length strings.
     * For unequal-length strings, returns a value larger than any
     * plausible minimum so the comparison is treated as "not close
     * enough to be a problem" (length mismatches are caught by a
     * separate check, so the value just needs to be consistent).
     */
    private static int hammingDistance(String a, String b) {
        if (a.length() != b.length()) {
            return Integer.MAX_VALUE
        }
        int dist = 0
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) dist++
        }
        return dist
    }

    /**
     * For every pair of indices in the named column, flag pairs
     * whose Hamming distance is below {@code minDistance}. Catches
     * demultiplexing risks where one sequencing error could
     * cross-assign a read to the wrong sample. Skips empty cells
     * (those are caught by the dedicated empty-index check) and
     * unequal-length indices (those are caught by the length check).
     *
     * @param samples the parsed sample list
     * @param columnLabel user-facing label for the column (e.g. "I7 (index)")
     * @param columnKey the map key in the sample map (e.g. "index")
     * @param errors the running list of error messages to append to
     * @param minDistance the minimum allowed Hamming distance between
     *                    any two indices in the column
     */
    private static void checkHammingDistances(
        List<Map<String, String>> samples,
        String columnLabel,
        String columnKey,
        List<String> errors,
        int minDistance
    ) {
        // Collect (sampleId, uppercasedIndex) pairs for non-empty
        // cells. Skip entries with invalid characters — those are
        // already flagged by the character check and would throw
        // off the distance comparison.
        List<List<String>> entries = []
        for (sample in samples) {
            String sampleId = sample.Sample_ID ?: sample.sample_id
            String raw = sample[columnKey]
            if (raw == null) continue
            String trimmed = raw.trim()
            if (!trimmed) continue
            String upper = trimmed.toUpperCase()
            // Only include sequences that are valid nucleotides; the
            // character check will have flagged any others.
            if (!upper.matches(/^[ACGTN]+$/)) continue
            entries << [sampleId, upper]
        }

        // Need at least 2 entries to compute a distance.
        if (entries.size() < 2) return

        // Compare all pairs. n^2 is fine — samplesheets have at
        // most a few thousand samples, and the comparison itself is
        // O(length).
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                int dist = hammingDistance(entries[i][1], entries[j][1])
                if (dist == 0) {
                    // Identical indices are already caught by the
                    // I7+I5 combination uniqueness check (which
                    // also fires when both I7 and I5 are the same).
                    // Skip here to avoid double-reporting.
                    continue
                }
                if (dist < minDistance) {
                    errors << "${columnLabel} Hamming distance violation: '${entries[i][1]}' (sample '${entries[i][0]}') and '${entries[j][1]}' (sample '${entries[j][0]}') differ by only ${dist} base(s); minimum allowed is ${minDistance} (a single sequencing error could misassign reads between these samples)"
                }
            }
        }
    }
}
