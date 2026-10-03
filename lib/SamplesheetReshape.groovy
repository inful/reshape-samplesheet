/**
 * Parses an Illumina samplesheet (bcl2fastq or Local Run Manager format)
 * and reshapes it into the generic nf-core samplesheet layout:
 *
 *     sample,fastq_1,fastq_2
 *
 * Fastq files are discovered by matching the Sample_ID against filenames
 * in a fastq directory. Multiple R1/R2 files for one sample (e.g. across
 * lanes) are joined with a comma into a single cell, per nf-core convention.
 *
 * Pure Groovy, no external runtime dependencies — Nextflow already provides
 * Groovy, so this is safe to call from a sub-workflow.
 *
 * <h2>Error contract</h2>
 * The class throws {@link IllegalArgumentException} for malformed input:
 * missing file, empty file, bcl2fastq with no {@code [Data]} section,
 * duplicate header columns, missing required columns, samples with no
 * matching fastq files, etc. All detected issues for a given samplesheet
 * are collected and reported in a single exception so the user sees the
 * complete list and can fix them in one pass.
 *
 * <h2>Pre-flight validation</h2>
 * Use {@link #validate} before a compute pipeline, and
 * {@link #validateBcl2fastq} before running bcl2fastq. The
 * {@code validateStructure: true} opt enables bcl2fastq structural
 * checks alongside the file matching in a single pass.
 *
 * <h2>Implementation layout</h2>
 * This class is a thin facade. The actual logic lives in
 * {@link SamplesheetParser}, {@link SamplesheetReshaper},
 * {@link SamplesheetValidator}, and {@link CsvSupport}. All four are
 * auto-loaded by Nextflow from the {@code lib/} directory.
 *
 * @author Jone Marius Vignes
 * @since 0.1.0
 */
class SamplesheetReshape {

    // ---- parseSamplesheet ----

    /**
     * Parse an Illumina samplesheet file.
     * Auto-detects bcl2fastq (has a [Data] section) vs Local Run Manager
     * (header line at row 0).
     */
    static List<Map<String, String>> parseSamplesheet(File file) {
        return SamplesheetParser.parseSamplesheetImpl(file)
    }

    /**
     * Path-like overload accepting a Nextflow {@code Path},
     * {@code String}, or {@code java.io.File}.
     */
    static List<Map<String, String>> parseSamplesheet(Object file) {
        return parseSamplesheet(CsvSupport.asFile(file))
    }

    // ---- validate (samplesheet only) ----

    /**
     * Parse the samplesheet and discard the result. Useful as a
     * pre-flight check before doing the more expensive reshape work.
     * Throws the same exceptions as {@link #parseSamplesheet(File)}.
     */
    static void validate(File samplesheet) {
        SamplesheetParser.parseSamplesheetImpl(samplesheet)
    }

    // ---- validate (samplesheet + fastq_dir) ----

    /**
     * Pre-flight check that runs every validation {@link #reshape} would
     * perform, but discards the output. Throws the same exceptions
     * (malformed samplesheet, missing/empty {@code fastq_dir}, samples
     * with no matching fastq files) so a pipeline can fail fast before
     * any compute work is submitted.
     *
     * <p><b>Best practice</b>: call this at the entry point of any
     * pipeline that uses {@link #reshape}, so the user gets a clear,
     * complete error before compute is billed. For a 1000-sample
     * variant-calling run, discovering at the end that "20 of 21
     * samples were processed" is the worst possible failure mode —
     * this method prevents it.</p>
     */
    static void validate(File samplesheet, File fastqDir) {
        validate(samplesheet, fastqDir, [:])
    }

    static void validate(File samplesheet, File fastqDir, Map opts) {
        // Run reshape but discard the output. If reshape throws, so
        // does this; if it returns successfully, all checks passed.
        reshape(samplesheet, fastqDir, opts)
    }

    static void validate(Object samplesheet) {
        validate(CsvSupport.asFile(samplesheet))
    }

    static void validate(Object samplesheet, Object fastqDir) {
        validate(CsvSupport.asFile(samplesheet), CsvSupport.asFile(fastqDir))
    }

    static void validate(Object samplesheet, Object fastqDir, Map opts) {
        validate(CsvSupport.asFile(samplesheet), CsvSupport.asFile(fastqDir), opts)
    }

    // ---- validateBcl2fastq ----

    /**
     * Pre-flight check for a bcl2fastq run: parse the samplesheet
     * (catches all the same structural problems as {@link #parseSamplesheet}
     * — empty file, bcl2fastq without [Data], duplicate columns, missing
     * Sample_ID) and additionally verify the bcl2fastq-specific structural
     * requirements: unique Sample_IDs, non-empty I7/I5 index values,
     * valid I7/I5 index sequences (only A/C/G/T/N, normalised to
     * uppercase), consistent I7/I5 lengths across samples, and unique
     * I7+I5 index combinations.
     *
     * <p>Use this <em>before</em> running bcl2fastq, when the only input
     * you have is the samplesheet and there are no fastq files yet. For
     * an end-of-pipeline pre-flight that also verifies fastq matches, use
     * {@link #validate(File, File, Map)} with {@code opts.validateStructure = true}.</p>
     *
     * <p>Same exception contract as {@link #reshape}: throws
     * {@link IllegalArgumentException} with a clear, complete error
     * message listing all issues at once.</p>
     */
    static void validateBcl2fastq(File samplesheet) {
        List<Map<String, String>> samples = SamplesheetParser.parseSamplesheetImpl(samplesheet)
        SamplesheetValidator.validateBcl2fastqChecks(samples, samplesheet)
    }

    static void validateBcl2fastq(Object samplesheet) {
        validateBcl2fastq(CsvSupport.asFile(samplesheet))
    }

    // ---- reshape ----

    /**
     * Reshape an Illumina samplesheet into a generic nf-core CSV.
     *
     * @param samplesheet the Illumina samplesheet
     * @param fastqDir directory containing fastq files
     * @return CSV body as a string, terminated with a trailing newline
     */
    static String reshape(File samplesheet, File fastqDir) {
        return SamplesheetReshaper.reshapeImpl(samplesheet, fastqDir, [:])
    }

    /**
     * Reshape with options.
     *
     * Recognised keys in {@code opts}:
     *  - {@code recursive} (Boolean, default {@code false}) — walk
     *    {@code fastqDir} recursively to find fastq files in subdirs.
     *  - {@code strandedness} (String, default {@code null}) — when
     *    non-null, a fourth {@code strandedness} column is added to
     *    the output and every sample row receives this value. Common
     *    nf-core values: {@code unstranded}, {@code forward},
     *    {@code reverse}, {@code auto}. No validation is performed;
     *    the downstream tool is the source of truth. A Boolean or
     *    empty-string value is coerced to null (catches the
     *    Nextflow `--strandedness ''` footgun).
     *  - {@code pattern} (String, default {@code null}) — when set,
     *    overrides the default matching strategy with a user-supplied
     *    regex template. The template may reference the Sample_ID as
     *    {@code \$\{sampleId\}} (any number of times); each occurrence
     *    is substituted with the regex-escaped Sample_ID. See the
     *    "matching strategy" section of the README for examples.
     *  - {@code validateStructure} (Boolean, default {@code false}) — when
     *    true, run the bcl2fastq-specific structural checks on the
     *    samplesheet: unique Sample_IDs, non-empty I7/I5 index values,
     *    I7/I5 index sequences made of only A/C/G/T/N, consistent
     *    I7/I5 lengths across samples, and unique I7+I5 index
     *    combinations. Useful for a combined pre-flight that
     *    validates both the samplesheet and the fastq matches in one
     *    pass. See {@link #validateBcl2fastq(File)} for the
     *    samplesheet-only version.
     * Unknown keys are silently ignored so future options can be
     * added without breaking callers.
     */
    static String reshape(File samplesheet, File fastqDir, Map opts) {
        return SamplesheetReshaper.reshapeImpl(samplesheet, fastqDir, opts ?: [:])
    }

    /**
     * Path-like overloads for {@link #reshape(File, File)} and
     * {@link #reshape(File, File, Map)}.
     */
    static String reshape(Object samplesheet, Object fastqDir) {
        return SamplesheetReshaper.reshapeImpl(
            CsvSupport.asFile(samplesheet),
            CsvSupport.asFile(fastqDir),
            [:]
        )
    }

    static String reshape(Object samplesheet, Object fastqDir, Map opts) {
        return SamplesheetReshaper.reshapeImpl(
            CsvSupport.asFile(samplesheet),
            CsvSupport.asFile(fastqDir),
            opts ?: [:]
        )
    }

    // ---- writeReshaped ----

    /**
     * Reshape and write to a stable filename inside outputDir.
     *
     * @return the resulting CSV file
     */
    static File writeReshaped(File outputDir, File samplesheet, File fastqDir) {
        return SamplesheetReshaper.writeReshapedImpl(outputDir, samplesheet, fastqDir, [:])
    }

    /**
     * Reshape and write with options. See {@link #reshape(File, File, Map)}
     * for recognised keys in {@code opts}.
     */
    static File writeReshaped(File outputDir, File samplesheet, File fastqDir, Map opts) {
        return SamplesheetReshaper.writeReshapedImpl(outputDir, samplesheet, fastqDir, opts ?: [:])
    }

    static File writeReshaped(Object outputDir, Object samplesheet, Object fastqDir) {
        return SamplesheetReshaper.writeReshapedImpl(
            CsvSupport.asFile(outputDir),
            CsvSupport.asFile(samplesheet),
            CsvSupport.asFile(fastqDir),
            [:]
        )
    }

    static File writeReshaped(Object outputDir, Object samplesheet, Object fastqDir, Map opts) {
        return SamplesheetReshaper.writeReshapedImpl(
            CsvSupport.asFile(outputDir),
            CsvSupport.asFile(samplesheet),
            CsvSupport.asFile(fastqDir),
            opts ?: [:]
        )
    }
}
