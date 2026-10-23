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

runner.test('a stray quote in an unquoted cell does not swallow the rows below it', {
    // CsvLines used to toggle "inside quotes" on EVERY quote character.
    // A stray quote in an unquoted field put it into that mode and ate
    // every following row into one logical line, which surfaced as a
    // cell-count error on the wrong line. CsvCell.parseLine already
    // treated a mid-cell quote as a literal; now the splitter agrees.
    def lines = CsvLines.splitLogicalLines(
        "[Data]\nSample_ID,index,index2\n" +
        "sample_A,ATCGACGT,GCTAGCTA\n" +
        "sample_B,foo\"bar,GGCTAACC\n" +
        "sample_C,AACCGGTT,CCAACCGG\n"
    )
    assert lines.size() == 5,
        "expected 5 logical lines, got ${lines.size()} — rows were swallowed: ${lines*.content}"
    assert lines[3].content == 'sample_B,foo"bar,GGCTAACC'
    assert lines[4].content == 'sample_C,AACCGGTT,CCAACCGG'
})

runner.test('a multi-line quoted cell is still one logical line', {
    // The counterpart to the fix above: a genuine newline inside a
    // quoted region must NOT split the row.
    def lines = CsvLines.splitLogicalLines(
        "h1,h2\n" +
        'sample_A,"line one' + "\n" + 'line two",ATCG' + "\n" +
        "sample_B,x,y\n"
    )
    assert lines.size() == 3,
        "expected 3 logical lines, got ${lines.size()}"
    assert lines[1].content.startsWith('sample_A,"line one')
    assert lines[1].content.endsWith('line two",ATCG')
})

runner.test('an escaped double-quote inside a quoted cell keeps the region open', {
    def lines = CsvLines.splitLogicalLines(
        "h1,h2\n" +
        'a,"say ""hi"" now",b' + "\n" +
        "c,d,e\n"
    )
    assert lines.size() == 3,
        "expected 3 logical lines, got ${lines.size()}: ${lines*.content}"
    assert lines[1].content == 'a,"say ""hi"" now",b'
})

runner.test('error messages name the real line number across blank lines', {
    // Blank lines are filtered before rows are parsed, so numbering
    // rows from the header counted them. Use the splitter's own line
    // numbers instead, or the user is pointed at the wrong row.
    def tmp = File.createTempFile('blankline', '.csv')
    tmp.text = '[Data]\n' +
               'Sample_ID,index,index2\n' +
               'sample_A,ATCG,GCTA\n' +
               '\n' +
               'sample_B,BROKEN\n'
    try {
        String msg = null
        try {
            SamplesheetReshape.parseSamplesheet(tmp)
        } catch (IllegalArgumentException e) {
            msg = e.message
        }
        assert msg != null, 'expected an exception for the malformed row'
        // The malformed row is on line 5 of the file.
        assert msg.contains('line 5'),
            "error should name line 5 (the actual row), got: ${msg}"
    } finally { tmp.delete() }
})

// -----------------------------------------------------------------------------
// read-direction classification
// -----------------------------------------------------------------------------
// isReadFile used to scan the WHOLE filename, sample-ID prefix included.
// A Sample_ID containing "_R1" or "_R2" therefore made its own files
// look like both directions, and the R1 reads were silently duplicated
// into fastq_2 — a well-formed CSV, a clean exit, wrong science.
println "\n[read-direction classification]"

runner.test('a Sample_ID containing _R2 does not duplicate R1 reads into fastq_2', {
    def root = Files.createTempDirectory('reshape_rid_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    def id = 'case_R2_ctrl'
    new File(root, "${id}_S1_L001_R1_001.fastq.gz").bytes = bytes
    new File(root, "${id}_S1_L001_R2_001.fastq.gz").bytes = bytes
    try {
        def ss = File.createTempFile('rid_sheet', '.csv')
        ss.setText("[Data]\nSample_ID,index,index2\n${id},ATCGACGT,GCTAGCTA\n", 'UTF-8')
        def outDir = Files.createTempDirectory('rid_out_').toFile()
        try {
            def csvFile = SamplesheetReshape.writeReshaped(outDir, ss, root)
            def records = SamplesheetReshape.parseReshapedCsv(csvFile)
            assert records.size() == 1
            def r = records[0]
            assert r.fastq_1.size() == 1,
                "expected exactly 1 R1 file, got ${r.fastq_1.size()}"
            assert r.fastq_2.size() == 1,
                "the R1 file was duplicated into fastq_2 (got ${r.fastq_2.size()}): ${r.fastq_2*.toString()}"
            assert r.fastq_1[0].toString().endsWith('_R1_001.fastq.gz')
            assert r.fastq_2[0].toString().endsWith('_R2_001.fastq.gz')
            assert r.meta.single_end == false
        } finally {
            ss.delete(); outDir.deleteDir()
        }
    } finally { root.deleteDir() }
})

runner.test('a Sample_ID containing _R1 does not pull R2 reads into fastq_1', {
    def root = Files.createTempDirectory('reshape_rid2_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    def id = 'cohort_R1_ctrl'
    new File(root, "${id}_S1_L001_R1_001.fastq.gz").bytes = bytes
    new File(root, "${id}_S1_L001_R2_001.fastq.gz").bytes = bytes
    try {
        def ss = File.createTempFile('rid2_sheet', '.csv')
        ss.setText("[Data]\nSample_ID,index,index2\n${id},ATCGACGT,GCTAGCTA\n", 'UTF-8')
        def outDir = Files.createTempDirectory('rid2_out_').toFile()
        try {
            def csvFile = SamplesheetReshape.writeReshaped(outDir, ss, root)
            def records = SamplesheetReshape.parseReshapedCsv(csvFile)
            assert records[0].fastq_1.size() == 1
            assert records[0].fastq_2.size() == 1
        } finally {
            ss.delete(); outDir.deleteDir()
        }
    } finally { root.deleteDir() }
})

runner.test('_R10 in a filename is still not counted as R1', {
    // The `[._]` guard after the read digit stops _R10_ matching R1.
    // Stripping the sample-ID prefix must not weaken it.
    def root = Files.createTempDirectory('reshape_r10_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    ['_R10_001', '_R1_001', '_R2_001'].each {
        new File(root, "sample_A_S1_L001${it}.fastq.gz").bytes = bytes
    }
    try {
        def ss = File.createTempFile('r10_sheet', '.csv')
        ss.setText("[Data]\nSample_ID,index,index2\nsample_A,ATCGACGT,GCTAGCTA\n", 'UTF-8')
        def outDir = Files.createTempDirectory('r10_out_').toFile()
        try {
            def records = SamplesheetReshape.parseReshapedCsv(
                SamplesheetReshape.writeReshaped(outDir, ss, root))
            assert records[0].fastq_1.size() == 1,
                "the _R10 file must not be counted as R1, got ${records[0].fastq_1.size()}"
            assert records[0].fastq_2.size() == 1
        } finally {
            ss.delete(); outDir.deleteDir()
        }
    } finally { root.deleteDir() }
})

runner.test('writeReshaped writes UTF-8 regardless of the platform charset', {
    // The reader reads UTF-8 explicitly (SamplesheetParser uses
    // getText('UTF-8')). The writer used to use plain `out.text =`,
    // which takes the JVM's platform default charset — so on any JVM
    // not defaulting to UTF-8 (older Docker images, LANG=C containers)
    // a non-ASCII sample name was silently destroyed on write.
    //
    // A round-trip assertion cannot catch that on a UTF-8 host: there
    // the buggy writer and the fixed one produce the SAME bytes, so
    // the test passed with the bug present (verified — it only failed
    // under -Dfile.encoding=ISO-8859-1, which nothing in bin/test.sh
    // or ci.yml set). The check therefore runs the writer in a
    // subprocess whose default charset is ISO-8859-1 and asserts the
    // bytes on disk are UTF-8. That fails with the bug and passes with
    // the fix, on any host.
    //
    // JAVA_TOOL_OPTIONS is how the flag reaches the JVM: the `groovy`
    // launcher sets -D properties too late for Charset.defaultCharset()
    // (which is read at JVM startup), so `groovy -Dfile.encoding=…`
    // changes the property but not the charset.
    //
    // Built from a codepoint so this test source stays pure ASCII and
    // cannot itself be mangled by a non-UTF-8 compiler.
    def id = "s" + (char) 0x00E4 + "mple_X"
    def root = Files.createTempDirectory('reshape_utf8_').toFile()
    new File(root, id + "_S1_L001_R1_001.fastq.gz").bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    def sheet = File.createTempFile('utf8_sheet', '.csv')
    sheet.setText("[Data]\nSample_ID,index,index2\n${id},ATCG,GCTA\n", 'UTF-8')
    def outDir = Files.createTempDirectory('utf8_out_').toFile()
    // Pure ASCII on purpose: the child JVM reads this script with its
    // (non-UTF-8) default charset.
    def probe = File.createTempFile('utf8_probe', '.groovy')
    probe.setText(
        'def out = SamplesheetReshape.writeReshaped(new File(args[0]), new File(args[1]), new File(args[2]))\n' +
        'print out.absolutePath\n', 'UTF-8')
    try {
        def pb = new ProcessBuilder([
            'groovy', '-cp', new File('lib').absolutePath, probe.absolutePath,
            outDir.absolutePath, sheet.absolutePath, root.absolutePath
        ])
        pb.environment().put('JAVA_TOOL_OPTIONS', '-Dfile.encoding=ISO-8859-1')
        def proc = pb.start()
        def stdout = new String(proc.inputStream.bytes, 'UTF-8')
        def stderr = new String(proc.errorStream.bytes, 'UTF-8')
        proc.waitFor()
        assert proc.exitValue() == 0, "probe failed: ${stderr}"
        def csvFile = new File(stdout.trim())
        assert csvFile.exists(), "probe wrote no CSV (stdout: '${stdout}', stderr: '${stderr}')"
        // Decoded as UTF-8 the file must contain the non-ASCII name
        // intact. A writer that used the platform charset wrote the
        // byte 0xE4 (ISO-8859-1) and this decode yields a replacement
        // character instead.
        def content = new String(csvFile.bytes, 'UTF-8')
        assert content.contains(id),
            "CSV is not UTF-8 — the non-ASCII sample name did not survive the write"
        // And the same file must still round-trip through the reader.
        assert SamplesheetReshape.parseReshapedCsv(csvFile)[0].meta.id == id,
            "parseReshapedCsv must recover the exact Sample_ID"
    } finally {
        probe.delete()
        sheet.delete()
        root.deleteDir()
        outDir.deleteDir()
    }
})

// -----------------------------------------------------------------------------
// RFC-4180 cell quoting on output — the regression lock for the
// pre-refactor behaviour where the writer quoted multi-value cells
// (r1/r2 with `,` inside) but did NOT quote sample IDs or strandedness
// values containing `,`, `"`, `\n`, or `\r`. Previously the unquoted
// sample-id round-tripped through ReshapedCsvParser into a row that
// was mis-aligned: the parser split on the inner separator and the
// meta id ended up as only the substring before the first comma.
// -----------------------------------------------------------------------------
println "\n[writer RFC-4180 cell quoting]"

runner.test('writer quotes a Sample_ID that contains the separator (round-trip-safe)',
            {
    // The load-bearing regression: a Sample_ID like "study_X,patient_Y"
    // contains the separator. The old writer would have emitted it
    // unquoted as `study_X,patient_Y,/r1,/r2` — when a downstream
    // parser split that on `,`, it would read 4 cells instead of 3
    // and the meta id would be silently truncated to "study_X". The
    // writer now RFC-4180-quotes any cell containing `,`, so the row
    // reads back as 3 cells with the full id intact.
    //
    // The fastq filenames must NOT contain the comma: the output CSV
    // joins several paths into one cell with `,` and the reader splits
    // that cell on every comma, so a path with a comma in it cannot be
    // read back (reshape refuses those, see "reshape refuses a fastq
    // path that contains a comma" below). The sample id therefore
    // cannot be part of the filename either, so the files are reached
    // through a custom pattern instead of the default prefix match.
    def root = Files.createTempDirectory('reshape_comma_sampleid_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    new File(root, 'L001_R1_001.fastq.gz').bytes = bytes
    new File(root, 'L001_R2_001.fastq.gz').bytes = bytes
    try {
        def tmp = File.createTempFile('comma_sample', '.csv')
        tmp.text = '''[Data]
Sample_ID,index,index2
"study_X,patient_Y",ATCG,GCTA
'''
        def csvFile
        try {
            // Drive the writer through the public writeReshaped path
            // so we test the same code as a real pipeline run. The
            // pattern matches every file: there is exactly one sample
            // in this fixture, so "the file that matches" is
            // unambiguous.
            def outDir = Files.createTempDirectory('comma_out_').toFile()
            csvFile = SamplesheetReshape.writeReshaped(
                outDir, tmp, root, [pattern: '.*']
            )
            String csv = csvFile.text
            def row = csv.readLines().drop(1).find { it.contains('study_X') }
            assert row != null, "row for the comma-id sample missing: ${csv}"
            assert row.startsWith('"study_X,patient_Y",'),
                "Sample_ID with embedded separator must be RFC-4180 quoted, got: ${row}"

            // Now verify the parser round-trips the writer's output
            // — this is the exact path the sub-workflow's `samples`
            // emit takes (RESHAPE_SAMPLESHEET.out.csv →
            // parseReshapedCsv → records).
            def records = SamplesheetReshape.parseReshapedCsv(csvFile)
            def record = records.find { it.meta.id?.contains('study_X') }
            assert record != null, "round-tripped record missing for the comma-id sample"
            assert record.meta.id == 'study_X,patient_Y',
                "round-trip should preserve the full Sample_ID with the comma intact, got: '${record.meta.id}'"
        } finally {
            tmp.delete()
            csvFile?.parentFile?.deleteDir()
        }
    } finally { root.deleteDir() }
})

// -----------------------------------------------------------------------------
// fastq paths and the comma separator
//
// The output CSV puts several paths in one cell, joined with `,`, and
// the reader splits that cell on every comma. A path that contains a
// comma therefore comes back as two paths that do not exist -- and the
// `samples` emit would hand those to a downstream module.
// -----------------------------------------------------------------------------
println "\n[comma in a fastq path]"

runner.test('reshape refuses a fastq path that contains a comma', {
    // The comma lives in a directory name, so the Sample_ID and the
    // filenames stay clean and the ONLY thing wrong is the path.
    def root = Files.createTempDirectory('reshape_comma_path_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    def commaDir = new File(root, 'run,2026')
    commaDir.mkdirs()
    new File(commaDir, 'sample_A_S1_L001_R1_001.fastq.gz').bytes = bytes
    new File(commaDir, 'sample_A_S1_L001_R2_001.fastq.gz').bytes = bytes
    def sheet = File.createTempFile('comma_path', '.csv')
    sheet.setText("[Data]\nSample_ID,index,index2\nsample_A,ATCG,GCTA\n", 'UTF-8')
    try {
        def msg = null
        try {
            SamplesheetReshape.reshape(sheet, commaDir)
        } catch (IllegalArgumentException e) {
            msg = e.message
        }
        assert msg != null,
            "expected an exception: a comma path cannot be written to the CSV"
        assert msg.contains('comma'),
            "error should explain the comma problem: ${msg}"
        assert msg.contains('run,2026'),
            "error should name the offending path: ${msg}"
    } finally {
        sheet.delete()
        root.deleteDir()
    }
})

// -----------------------------------------------------------------------------
// one file, one sample
//
// The default matcher only looks at the character right after the
// Sample_ID, so "A" matches "A_1_S1_L001_R1_001.fastq.gz" as well as
// "A_S1_...". Both samples then claimed the same file: two samples,
// one set of reads, a well-formed CSV and a clean exit.
// -----------------------------------------------------------------------------
println "\n[one fastq file, one sample]"

runner.test('a file that matches two Sample_IDs goes to the longest one, and the other is reported', {
    def root = Files.createTempDirectory('reshape_two_samples_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    new File(root, 'A_1_S1_L001_R1_001.fastq.gz').bytes = bytes
    new File(root, 'A_1_S1_L001_R2_001.fastq.gz').bytes = bytes
    try {
        def tmp = File.createTempFile('two_samples', '.csv')
        tmp.text = '''[Data]
Sample_ID,index,index2
A,ATCG,GCTA
A_1,GGCC,TGAA
'''
        try {
            def msg = null
            try {
                SamplesheetReshape.reshape(tmp, root)
            } catch (IllegalArgumentException e) {
                msg = e.message
            }
            assert msg != null, "expected an exception: 'A' has no fastq of its own"
            assert msg.trim().endsWith('A'),
                "only 'A' should be reported as having no matching fastqs: ${msg}"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

runner.test('a sample and a longer sample id with the same prefix each keep their own files', {
    def root = Files.createTempDirectory('reshape_prefix_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    new File(root, 'A_S1_L001_R1_001.fastq.gz').bytes = bytes
    new File(root, 'A_S1_L001_R2_001.fastq.gz').bytes = bytes
    new File(root, 'A_1_S1_L001_R1_001.fastq.gz').bytes = bytes
    new File(root, 'A_1_S1_L001_R2_001.fastq.gz').bytes = bytes
    try {
        def tmp = File.createTempFile('prefix_samples', '.csv')
        tmp.text = '''[Data]
Sample_ID,index,index2
A,ATCG,GCTA
A_1,GGCC,TGAA
'''
        try {
            def rows = SamplesheetReshape.reshape(tmp, root).readLines().drop(1)
            def rowA = rows.find { it.startsWith('A,') }
            def rowA1 = rows.find { it.startsWith('A_1,') }
            assert rowA != null, "row for A missing: ${rows}"
            assert rowA1 != null, "row for A_1 missing: ${rows}"
            assert rowA.contains('A_S1_L001_R1_001') && !rowA.contains('A_1_S1'),
                "A must only get its own files, got: ${rowA}"
            assert rowA1.contains('A_1_S1_L001_R1_001') && !rowA1.contains('/A_S1'),
                "A_1 must only get its own files, got: ${rowA1}"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

runner.test('writer quotes a strandedness value that contains the separator',
            {
    // A strandedness value like "for,ward" — pathological but a
    // strict test of the contract. Without quoting, the row would
    // have 5 cells (sample, r1, r2, "for", "ward") and the trailing
    // column would have fallen off.
    def root = Files.createTempDirectory('reshape_comma_stranded_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    new File(root, 'sample_A_S1_L001_R1_001.fastq.gz').bytes = bytes
    new File(root, 'sample_A_S1_L001_R2_001.fastq.gz').bytes = bytes
    try {
        def tmp = File.createTempFile('comma_stranded', '.csv')
        tmp.text = '''[Data]
Sample_ID,index,index2
sample_A,ATCG,GCTA
'''
        try {
            String csv = SamplesheetReshape.reshape(
                tmp, root,
                [strandedness: 'for,ward']
            )
            def header = csv.readLines()[0]
            assert header == 'sample,fastq_1,fastq_2,strandedness',
                "header should still be 4 columns, got: '${header}'"
            def row = csv.readLines().drop(1).find { it.startsWith('sample_A,') }
            assert row != null
            assert row.endsWith(',"for,ward"'),
                "strandedness with embedded separator must be quoted; got: '${row}'"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

System.exit(runner.summary())
