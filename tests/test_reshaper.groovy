// Tests for SamplesheetReshape reshape + writeReshaped — the core
// file-matching logic, including the strict failure modes (throws on
// missing fastqs, missing/empty fastq_dir, missing samples).
//
// Run with:  groovy -cp lib tests/test_reshaper.groovy
//
// Exit code 0 on success, non-zero on failure.

import java.nio.file.Files

def ctx = evaluate(new File('tests/test_runner.groovy'))
def dataDir = ctx.dataDir
def fastqDir = ctx.fastqDir
def runner = ctx.runner

// -----------------------------------------------------------------------------
// reshape — happy paths
// -----------------------------------------------------------------------------
println "\n[reshape]"

runner.test('paired-end dataset emits sample,fastq_1,fastq_2 header', {
    def csv = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir
    )
    def header = csv.readLines()[0]
    assert header == 'sample,fastq_1,fastq_2', "unexpected header: '${header}'"
})

runner.test('multi-lane sample aggregates R1 fastqs into a single comma-separated cell', {
    def csv = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir
    )
    def rows = csv.readLines().drop(1)
    def sampleA = rows.find { it.startsWith('sample_A,') }
    assert sampleA != null, "sample_A row missing"
    // Cells are comma-separated but the fastq_1 cell itself contains commas
    // (multiple lanes), so we cannot use split(',') — check the raw row.
    assert sampleA.contains('L001_R1') && sampleA.contains('L002_R1'),
        "expected both lanes referenced for sample_A, got: ${sampleA}"
    assert sampleA.contains('L001_R2') && sampleA.contains('L002_R2'),
        "expected both R2 lanes referenced for sample_A, got: ${sampleA}"
})

runner.test('multi-lane cells are RFC-4180 quoted so CSV consumers can parse them back as one cell', {
    // Per RFC 4180, a cell containing the separator (`,`) must be
    // wrapped in double quotes — otherwise Nextflow's `splitCsv(header:
    // true)` (and most other CSV consumers) miscount the columns and
    // silently drop the trailing values. The `per nf-core convention`
    // claim in the README is shorthand for "quoted multi-value cells",
    // matching rnaseq, sarek, ampliseq, etc.
    def csv = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir
    )
    def sampleA = csv.readLines().drop(1).find { it.startsWith('sample_A,') }
    assert sampleA != null, "sample_A row missing"
    // The fastq_1 and fastq_2 cells (which hold 2 paths each, joined
    // by `,`) must be wrapped in `"…"`. The sample id cell holds a
    // single token and is left unquoted.
    assert sampleA =~ /^sample_A,"[^"]+,[^"]+","[^"]+,[^"]+"$/,
        "expected sample_A row to have RFC-4180 quoted multi-value cells, got: ${sampleA}"
    // The converse: a single-lane sample (sample_B) has single-value
    // cells and must NOT be quoted. Assert the row contains no
    // quote characters (the column separators are commas between
    // unquoted cells, not inside quoted cells).
    def sampleB = csv.readLines().drop(1).find { it.startsWith('sample_B,') }
    assert sampleB != null, "sample_B row missing"
    assert !sampleB.contains('"'),
        "single-value cells should be left unquoted, got: ${sampleB}"
})

runner.test('single-end sample has empty fastq_2 cell', {
    def csv = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir
    )
    def rows = csv.readLines().drop(1)
    def sampleC = rows.find { it.startsWith('sample_C,') }
    assert sampleC != null, "sample_C row missing"
    assert sampleC.endsWith(','), "expected single-end row to end with ',', got: '${sampleC}'"
})

runner.test('unrelated files in the fastq_dir are ignored', {
    def csv = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir
    )
    assert !csv.contains('unrelated'), "unrelated file leaked into output"
})

runner.test('LRM and bcl2fastq inputs produce the same set of rows for the same fastq_dir', {
    def csvBcl = SamplesheetReshape.reshape(new File(dataDir, 'illumina_bcl2fastq.csv'), fastqDir)
    def csvLrm = SamplesheetReshape.reshape(new File(dataDir, 'illumina_lrm.csv'), fastqDir)
    // Sort rows so the test is independent of sample order in the input.
    // Header is excluded so it doesn't dominate the comparison.
    def rowsOnly = { String s -> s.readLines().drop(1).sort() }
    assert rowsOnly(csvBcl) == rowsOnly(csvLrm),
        "bcl2fastq and LRM should produce the same reshaped rows"
})

// -----------------------------------------------------------------------------
// reshape — strict failure modes (errors, not silent skips)
// -----------------------------------------------------------------------------
println "\n[reshape — strict failure modes]"

runner.test('sample with no matching fastqs is now an error, not a silent skip', {
    def tmp = File.createTempFile('samplesheet', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,Sample_Plate,Sample_Well,I7_Index_ID,index,I5_Index_ID,index2,Sample_Project,Description
sample_A,sample_A,,,UDI0001,ATCGACGT,UDI0001,GCTAGCTA,project_x,
ghost_sample,ghost_sample,,,UDI0002,GGCTAACC,UDI0002,TGACCGAA,project_x,
'''
    try {
        String msg = null
        try {
            SamplesheetReshape.reshape(tmp, fastqDir)
        } catch (IllegalArgumentException e) {
            msg = e.message
        }
        assert msg != null, "expected an exception for a sample with no fastq matches"
        assert msg.contains('ghost_sample'),
            "error should list ghost_sample as missing: ${msg}"
        // sample_A has matching fastqs and should not be in the error
        assert !msg.contains('sample_A,'),
            "sample_A has matching fastqs and should not be in the error: ${msg}"
    } finally {
        tmp.delete()
    }
})

runner.test('reshape error lists ALL samples with no fastq matches, not just the first', {
    def root = Files.createTempDirectory('reshape_all_missing_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    new File(root, 'sample_A_S1_L001_R1_001.fastq.gz').bytes = bytes
    new File(root, 'sample_A_S1_L001_R2_001.fastq.gz').bytes = bytes
    try {
        def tmp = File.createTempFile('two_samples', '.csv')
        tmp.text = '''[Data]
Sample_ID,index,index2
sample_A,ATCG,GCTA
sample_B,GGCC,TGAA
'''
        try {
            String msg = null
            try {
                SamplesheetReshape.reshape(tmp, root)
            } catch (IllegalArgumentException e) {
                msg = e.message
            }
            assert msg != null, "expected an exception"
            assert msg.contains('sample_B'),
                "sample_B should be listed as missing: ${msg}"
            // sample_A is fine and should not appear in the error
            assert !msg.contains('sample_A,'),
                "sample_A has matching fastqs and should not be in the error: ${msg}"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

runner.test('reshape with non-existent fastq_dir now throws (not warns)', {
    String msg = null
    try {
        SamplesheetReshape.reshape(
            new File(dataDir, 'illumina_bcl2fastq.csv'),
            new File('/definitely/does/not/exist/anywhere/at/all')
        )
    } catch (IllegalArgumentException e) {
        msg = e.message
    }
    assert msg != null, "expected an exception for missing fastq_dir"
    assert msg.contains('does not exist') || msg.contains('not a directory'),
        "error should explain the fastq_dir problem: ${msg}"
})

runner.test('reshape with empty fastq_dir now throws (not warns)', {
    def emptyDir = Files.createTempDirectory('reshape_empty_').toFile()
    try {
        String msg = null
        try {
            SamplesheetReshape.reshape(
                new File(dataDir, 'illumina_bcl2fastq.csv'),
                emptyDir
            )
        } catch (IllegalArgumentException e) {
            msg = e.message
        }
        assert msg != null, "expected an exception for empty fastq_dir"
        assert msg.contains('no .fastq') || msg.contains('contains no'),
            "error should explain the empty fastq_dir: ${msg}"
    } finally { emptyDir.deleteDir() }
})

runner.test('reshape collects ALL per-sample errors into a single exception', {
    // 4 samples in the samplesheet, only 2 have fastqs.
    // The error should mention both missing samples at once.
    def root = Files.createTempDirectory('reshape_collect_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    new File(root, 'sample_A_S1_L001_R1_001.fastq.gz').bytes = bytes
    new File(root, 'sample_A_S1_L001_R2_001.fastq.gz').bytes = bytes
    new File(root, 'sample_B_S2_L001_R1_001.fastq.gz').bytes = bytes
    new File(root, 'sample_B_S2_L001_R2_001.fastq.gz').bytes = bytes
    try {
        def tmp = File.createTempFile('four_samples', '.csv')
        tmp.text = '''[Data]
Sample_ID,index,index2
sample_A,ATCG,GCTA
sample_B,GGCC,TGAA
sample_C,AACC,CCAA
sample_D,TTAA,AATT
'''
        try {
            String msg = null
            try {
                SamplesheetReshape.reshape(tmp, root)
            } catch (IllegalArgumentException e) {
                msg = e.message
            }
            assert msg != null, "expected an exception"
            assert msg.contains('sample_C') && msg.contains('sample_D'),
                "error should list BOTH missing samples at once: ${msg}"
            // sample_A and sample_B have fastqs and should not be in the error
            assert !msg.contains('sample_A,') && !msg.contains('sample_B,'),
                "samples with fastqs should not appear in the error: ${msg}"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

// -----------------------------------------------------------------------------
// reshape — output format & opts handling
// -----------------------------------------------------------------------------
println "\n[reshape — output format & opts]"

runner.test('reshape with non-existent fastq_dir emits no warning (it throws instead)', {
    // After the strictness change, reshape throws on a missing fastq_dir
    // rather than warning. We assert no warnings were printed (the
    // exception is the user signal now).
    def original = System.err
    def buffer = new ByteArrayOutputStream()
    System.setErr(new PrintStream(buffer))
    boolean threw = false
    try {
        SamplesheetReshape.reshape(
            new File(dataDir, 'illumina_bcl2fastq.csv'),
            new File('/definitely/does/not/exist/anywhere/at/all')
        )
    } catch (IllegalArgumentException) {
        threw = true
    } finally {
        System.setErr(original)
    }
    assert threw, "expected reshape to throw on a missing fastq_dir"
    def warnings = buffer.toString().readLines().count { it.startsWith('WARN') }
    assert warnings == 0,
        "strict reshape should not print any WARN, got ${warnings}:\n${buffer}"
})

runner.test('opts=null is treated as the default empty map', {
    def explicit = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir
    )
    def withNull = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir,
        null
    )
    assert explicit == withNull, "opts=null should be equivalent to the default"
})

runner.test('reshape returns a trailing newline', {
    def csv = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir
    )
    assert csv.endsWith('\n'), "expected output to end with a newline"
})

// -----------------------------------------------------------------------------
// writeReshaped
// -----------------------------------------------------------------------------
println "\n[writeReshaped]"

runner.test('writeReshaped creates a file in the given output dir', {
    def outDir = Files.createTempDirectory('reshape_test_').toFile()
    try {
        def outFile = SamplesheetReshape.writeReshaped(
            outDir,
            new File(dataDir, 'illumina_bcl2fastq.csv'),
            fastqDir
        )
        assert outFile.exists()
        assert outFile.parentFile == outDir
        def firstLine = outFile.text.readLines()[0]
        assert firstLine == 'sample,fastq_1,fastq_2'
    } finally {
        outDir.deleteDir()
    }
})

runner.test('writeReshaped creates the output dir if it does not exist', {
    def baseDir = Files.createTempDirectory('reshape_test_').toFile()
    def outDir = new File(baseDir, 'nested/sub')
    try {
        assert !outDir.exists()
        def outFile = SamplesheetReshape.writeReshaped(
            outDir,
            new File(dataDir, 'illumina_bcl2fastq.csv'),
            fastqDir
        )
        assert outFile.exists()
    } finally {
        baseDir.deleteDir()
    }
})

System.exit(runner.summary())
