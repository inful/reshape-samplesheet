/**
 * Internal reshaper — produces the nf-core samplesheet CSV from an
 * Illumina samplesheet and a directory of fastq files. Not part of the
 * public API; called from {@link SamplesheetReshape}.
 *
 * <p>CSV serialization is delegated to {@link CsvCell#quote} for
 * cell-level RFC-4180 quoting. The reader counterpart
 * ({@link ReshapedCsvParser}) parses with {@link CsvCell#parseLine},
 * which is also the parser used for input samplesheets by
 * {@link SamplesheetParser}. All four sites — input parser, output
 * parser, output writer — share one RFC-4180 contract.</p>
 */
class SamplesheetReshaper {

    private static final List<String> FASTQ_SUFFIXES = [
        '.fastq.gz', '.fq.gz', '.fastq', '.fq'
    ]

    static String reshapeImpl(File samplesheet, File fastqDir, Map opts) {
        boolean recursive = opts.recursive == true
        // Coerce strandedness to a "real" value or null. We reject
        // the footguns:
        //   - Boolean true     (from `--strandedness` with no value)
        //   - empty string     (would write a column with empty value)
        //   - String "true"    (from `--strandedness ''`, which
        //                       Nextflow collapses to the literal "true")
        //   - String "false"   (also meaningless as a strandedness)
        // Anything else (unstranded, forward, reverse, auto, etc.) is
        // passed through verbatim. Non-Boolean non-String values fall
        // through to null too.
        String strandedness = null
        if (opts.strandedness instanceof String) {
            String s = (String) opts.strandedness
            if (!s.isEmpty() &&
                !s.equalsIgnoreCase('true') &&
                !s.equalsIgnoreCase('false')) {
                strandedness = s
            }
        }
        String pattern = opts.pattern  // null = use the strict default

        // fastq_dir issues are immediate, structural problems: there
        // is no point iterating over samples if we have no data to
        // match them against.
        if (fastqDir != null && (!fastqDir.exists() || !fastqDir.isDirectory())) {
            throw new IllegalArgumentException(
                "fastq_dir does not exist or is not a directory: " +
                "${fastqDir.absolutePath}"
            )
        }
        List<File> fastqFiles = collectFastqs(fastqDir, recursive)
        if (fastqDir != null && fastqFiles.isEmpty()) {
            // Distinguish "no fastqs here" from "we could not look".
            // On an unreadable directory listFiles() yields nothing, so
            // the generic message below would send the user hunting for
            // missing files when the real problem is a permission bit.
            if (!fastqDir.canRead()) {
                throw new IllegalArgumentException(
                    "fastq_dir is not readable (check permissions): " +
                    "${fastqDir.absolutePath}"
                )
            }
            throw new IllegalArgumentException(
                "fastq_dir contains no .fastq/.fq(.gz) files: " +
                "${fastqDir.absolutePath}"
            )
        }
        // parseSamplesheet throws on its own malformed-input problems
        // (empty file, bcl2fastq no [Data], duplicate columns, etc.).
        List<Map<String, String>> samples = SamplesheetParser.parseSamplesheetImpl(samplesheet)
        // bcl2fastq-specific structural checks (sample ID uniqueness,
        // index sequence format and consistency). Enabled via
        // `opts.validateStructure` so reshape stays focused on file
        // matching by default. Hamming distance violations are
        // warnings by default; pass `opts.hammingDistanceAsError: true`
        // to promote them to errors.
        if (opts.validateStructure == true) {
            boolean hammingAsError = opts.hammingDistanceAsError == true
            SamplesheetValidator.validateBcl2fastqChecks(
                samples,
                samplesheet,
                SamplesheetValidator.DEFAULT_MIN_HAMMING_DISTANCE,
                hammingAsError
            )
        }

        // Collect ALL per-sample problems and report them in one error.
        // Silently skipping these is the failure mode that wastes compute
        // and produces wrong scientific output: the pipeline runs, but
        // some samples don't get processed and the user only finds out
        // after the run.
        List<String> missingSamples = []
        List<Map> validSamples = []  // each: [sampleId: String, r1: String, r2: String]

        samples.each { Map<String, String> sample ->
            String sampleId = sample.Sample_ID ?: sample.sample_id
            List<File> matches = fastqFiles.findAll { File f ->
                matchesSampleId(f.name, sampleId, pattern)
            }
            if (matches.isEmpty()) {
                missingSamples << sampleId
                return
            }
            // Build the per-direction cells as a `,`-joined list of
            // paths. Cell-level RFC-4180 quoting is the writer's
            // responsibility (CsvCell.quote), not the matcher —
            // keeps the matching logic and the serialization
            // concern in separate functions.
            String r1 = matches.findAll { isReadFile(it.name, 1) }
                                  .collect { it.absolutePath }.join(',')
            String r2 = matches.findAll { isReadFile(it.name, 2) }
                                  .collect { it.absolutePath }.join(',')
            validSamples << [sampleId: sampleId, r1: r1, r2: r2]
        }

        if (!missingSamples.isEmpty()) {
            throw new IllegalArgumentException(
                "Cannot reshape samplesheet ${samplesheet.absolutePath}: " +
                "${missingSamples.size()} sample(s) have no matching fastq files " +
                "in ${fastqDir.absolutePath}:\n  " +
                missingSamples.join('\n  ')
            )
        }

        // We now know everything is valid; build the output. Every
        // cell goes through CsvCell.quote so values containing the
        // separator `,`, an embedded `"`, `\n`, or `\r` are emitted
        // as RFC-4180 quoted (with `"` doubled to `""`). Without
        // this, a Sample_ID containing `,` (or a strandedness value
        // that does) would produce a row that, when later parsed
        // back through the lib's ReshapedCsvParser, silently ends up
        // mis-aligned: the parser would split the row on the inner
        // separator and the meta id would be only the substring
        // before the first comma, with the rest of the row falling
        // off into phantom columns.
        //
        // Header cells are also routed through CsvCell.quote for
        // symmetry; for known headers (`sample`, `fastq_1`,
        // `fastq_2`, `strandedness`) it's a no-op since none of
        // them contain a special character.
        List<String> header = ['sample', 'fastq_1', 'fastq_2']
        if (strandedness != null) {
            header << 'strandedness'
        }
        List<List<String>> rows = [header]
        validSamples.each { v ->
            List<String> row = [v.sampleId, v.r1, v.r2]
            if (strandedness != null) {
                row << strandedness
            }
            rows << row
        }

        StringBuilder out = new StringBuilder()
        rows.each { List<String> row ->
            out.append(row.collect { CsvCell.quote(it) }.join(','))
            out.append('\n')
        }

        return out.toString()
    }

    static File writeReshapedImpl(File outputDir, File samplesheet, File fastqDir, Map opts) {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        String base = samplesheet.name.replaceFirst(~/\.[^.]+$/, '')
        File out = new File(outputDir, "${base}.nfcore.csv")
        // Write UTF-8 explicitly, mirroring the read side
        // (SamplesheetParser reads with getText('UTF-8')). Plain
        // `out.text =` uses the JVM's platform default charset, which
        // varies by environment and silently destroys non-ASCII
        // sample names on any JVM not defaulting to UTF-8 (older
        // Docker images, LANG=C containers).
        out.setText(reshapeImpl(samplesheet, fastqDir, opts), 'UTF-8')
        return out
    }

    private static List<File> collectFastqs(File dir, boolean recursive) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return []
        }
        if (recursive) {
            List<File> result = []
            dir.eachFileRecurse(groovy.io.FileType.FILES) { File f ->
                if (FASTQ_SUFFIXES.any { f.name.endsWith(it) }) {
                    result << f
                }
            }
            return result
        }
        return (dir.listFiles() as List<File>).findAll { File f ->
            FASTQ_SUFFIXES.any { f.name.endsWith(it) }
        }
    }

    /**
     * Match a filename as R1 or R2. Accepts the bcl2fastq style
     * (_R1_001.fastq.gz) and the simpler _R1.fastq.gz / _1.fastq.gz styles.
     * The character class `[._]` after the read digit guards against
     * false positives like _R10_ matching R1.
     */
    private static boolean isReadFile(String name, int read) {
        String r = String.valueOf(read)
        return (name =~ /_R${r}[._]/) ||
               (name =~ /_${r}[._]fastq/)
    }

    /**
     * Decide whether a fastq filename belongs to a given Sample_ID.
     *
     * Default behaviour (no {@code pattern} option): the filename must
     * <em>start with</em> the Sample_ID, and the character right after
     * must be a separator ({@code _} or {@code .}) or the end of the
     * string. This catches the documented footgun where a sample named
     * "A" would otherwise match "ABC_S1_L001_R1_001.fastq.gz" via
     * substring matching.
     *
     * When a {@code pattern} is supplied, the filename is tested
     * against the regex after substituting {@code \$\{sampleId\}}
     * with the (regex-escaped) Sample_ID. The pattern may reference
     * {@code \$\{sampleId\}} more than once.
     */
    private static boolean matchesSampleId(String filename, String sampleId, String pattern) {
        if (pattern != null) {
            // Interpolate the (regex-escaped) sampleId into the pattern.
            // Pattern.quote adds \Q and \E which are recognised by the
            // Java regex engine, so a Sample_ID like "v1.0" matches
            // literally. quoteReplacement handles $ and \ in the
            // replacement string itself (since we use replaceAll).
            String escaped = java.util.regex.Pattern.quote(sampleId)
            String interpolated = pattern.replaceAll(
                '\\$\\{sampleId\\}',
                java.util.regex.Matcher.quoteReplacement(escaped)
            )
            return filename ==~ interpolated
        }
        if (!filename.startsWith(sampleId)) {
            return false
        }
        if (filename.length() == sampleId.length()) {
            return true  // exact match (no suffix)
        }
        char next = filename.charAt(sampleId.length())
        return next == '_' || next == '.'
    }
}
