// Tests for SamplesheetReshape parsing — covers the `parseSamplesheet` API
// (happy path + malformed input) and the related `validate(File)` overload.
//
// Run with:  groovy -cp lib tests/test_parser.groovy
//
// Exit code 0 on success, non-zero on failure.

def ctx = evaluate(new File('tests/test_runner.groovy'))
def dataDir = ctx.dataDir
def fastqDir = ctx.fastqDir
def runner = ctx.runner

// -----------------------------------------------------------------------------
// parseSamplesheet — happy paths
// -----------------------------------------------------------------------------
println "\n[parseSamplesheet]"

runner.test('bcl2fastq: extracts 4 samples with expected columns', {
    def samples = SamplesheetReshape.parseSamplesheet(new File(dataDir, 'illumina_bcl2fastq.csv'))
    assert samples.size() == 4, "expected 4 samples, got ${samples.size()}"
    assert samples[0].Sample_ID == 'sample_A'
    assert samples[0].index == 'ATCGACGT'
    assert samples[0].index2 == 'GCTAGCTA'
    assert samples[0].Sample_Project == 'project_x'
})

runner.test('bcl2fastq: ignores [Header], [Reads], [Manifests] sections', {
    def samples = SamplesheetReshape.parseSamplesheet(new File(dataDir, 'illumina_bcl2fastq.csv'))
    samples.each {
        assert !it.containsKey('IEMFileVersion')
        assert !it.containsKey('Date')
        assert !it.containsKey('Read1Cycles')
    }
})

runner.test('LRM: parses headerless section format', {
    def samples = SamplesheetReshape.parseSamplesheet(new File(dataDir, 'illumina_lrm.csv'))
    assert samples.size() == 4
    assert samples[2].Sample_ID == 'sample_C'
    assert samples[2].SampleType == 'DNA'
})

runner.test('BCLConvert V2: extracts 4 samples from [BCLConvert_Data] section', {
    def samples = SamplesheetReshape.parseSamplesheet(
        new File(dataDir, 'illumina_bclconvert_v2.csv')
    )
    assert samples.size() == 4, "expected 4 samples, got ${samples.size()}"
    assert samples[0].Sample_ID == 'sample_A'
    assert samples[0].index == 'ATCGACGT'
    assert samples[0].index2 == 'GCTAGCTA'
})

runner.test('BCLConvert V2: silently ignores [Header] and [BCLConvert_Settings] sections', {
    // V2 sheets have metadata in [Header] (FileFormatVersion) and
    // [BCLConvert_Settings] (SoftwareVersion, OverrideCycles).
    // These are useful for the sequencer but not for the reshape
    // use case — verify they don't leak into sample records.
    def samples = SamplesheetReshape.parseSamplesheet(
        new File(dataDir, 'illumina_bclconvert_v2.csv')
    )
    samples.each { sample ->
        assert !sample.containsKey('FileFormatVersion'),
            "FileFormatVersion from [Header] should not appear in sample records"
        assert !sample.containsKey('SoftwareVersion'),
            "SoftwareVersion from [BCLConvert_Settings] should not appear in sample records"
        assert !sample.containsKey('OverrideCycles'),
            "OverrideCycles from [BCLConvert_Settings] should not appear in sample records"
        assert !sample.containsKey('RunName'),
            "RunName from [Header] should not appear in sample records"
    }
})

runner.test('BCLConvert V2: [Cloud_Data] is accepted as an alternative V2 data section', {
    // Some cloud-based BCLConvert runs use [Cloud_Data] instead of
    // [BCLConvert_Data]. Both should be detected as V2.
    def tmp = File.createTempFile('v2_cloud_data', '.csv')
    tmp.text = '''[Header]
FileFormatVersion,2

[BCLConvert_Settings]
SoftwareVersion,4.2.7

[Cloud_Data]
Sample_ID,Sample_Name,index,index2
sample_X,sample_X,ATCGACGT,GCTAGCTA
'''
    try {
        def samples = SamplesheetReshape.parseSamplesheet(tmp)
        assert samples.size() == 1
        assert samples[0].Sample_ID == 'sample_X'
        assert samples[0].index == 'ATCGACGT'
    } finally { tmp.delete() }
})

runner.test('BCLConvert V2: works with the bcl2fastq structural validator', {
    // The validator should run on V2 samplesheets the same as V1.
    SamplesheetReshape.validateBcl2fastq(
        new File(dataDir, 'illumina_bclconvert_v2.csv')
    )
    // No exception = success
})

runner.test('BCLConvert V2: end-to-end reshape produces an nf-core CSV with all 4 samples', {
    // The V2 format must round-trip through the lib's reshape
    // exactly like V1. This is the load-bearing integration test
    // for V2 support.
    def outDir = new File(fastqDir.parentFile, 'v2_test_out')
    if (outDir.exists()) outDir.deleteDir()
    outDir.mkdirs()
    try {
        def csvFile = SamplesheetReshape.writeReshaped(
            outDir,
            new File(dataDir, 'illumina_bclconvert_v2.csv'),
            fastqDir
        )
        def lines = csvFile.text.readLines()
        assert lines[0] == 'sample,fastq_1,fastq_2',
            "expected nf-core header, got: ${lines[0]}"
        // 4 data rows for the 4 samples in the V2 fixture
        assert lines.size() == 5, "expected 4 data rows + header, got: ${lines.size()}"
    } finally {
        outDir.deleteDir()
    }
})

runner.test('V2 takes precedence over V1 when both section markers are present', {
    // A malformed file that has both V1 and V2 sections. The
    // parser must pick the V2 data section (because BCLConvert
    // is what newer Illumina platforms emit, and a file with
    // both is most likely a V2 file that has V1-style metadata
    // sections copied in by mistake). Documents the precedence
    // rule in code rather than just in a comment.
    def tmp = File.createTempFile('v2_takes_precedence', '.csv')
    tmp.text = '''[Header]
IEMFileVersion,4

[Data]
Sample_ID,index,index2
sample_V1,ATCGACGT,GCTAGCTA

[BCLConvert_Data]
Sample_ID,index,index2
sample_V2,ATCGACGT,GCTAGCTA
'''
    try {
        def records = SamplesheetReshape.parseSamplesheet(tmp)
        assert records.size() == 1, "expected only the V2 sample, got: ${records.size()}"
        assert records[0].Sample_ID == 'sample_V2',
            "V2 sample should be parsed (V1 should be ignored), got: ${records[0].Sample_ID}"
    } finally { tmp.delete() }
})

runner.test('V2 with only [BCLConvert_Data] (minimal V2, no [Header] or [BCLConvert_Settings]) parses', {
    // The minimal valid V2 samplesheet has only the data section.
    // The optional config sections ([Header], [BCLConvert_Settings])
    // are present in the fixture but aren't required.
    def tmp = File.createTempFile('v2_minimal', '.csv')
    tmp.text = '''[BCLConvert_Data]
Sample_ID,index,index2
sample_A,ATCGACGT,GCTAGCTA
sample_B,GGCTAACC,TGACCGAA
'''
    try {
        def records = SamplesheetReshape.parseSamplesheet(tmp)
        assert records.size() == 2, "expected 2 samples, got: ${records.size()}"
        assert records[0].Sample_ID == 'sample_A'
        assert records[1].Sample_ID == 'sample_B'
        assert records[0].index == 'ATCGACGT'
    } finally { tmp.delete() }
})

runner.test('V2 with a bad index character is caught by the bcl2fastq validator', {
    // The validator should catch index errors in V2 records the same
    // way it does for V1 — because the per-sample data structure is
    // identical, the validator's checks apply unchanged. Without
    // this test, a regression where V2 records get into a different
    // shape (e.g., a column rename) wouldn't be caught.
    def tmp = File.createTempFile('v2_bad_index', '.csv')
    tmp.text = '''[Header]
FileFormatVersion,2

[BCLConvert_Data]
Sample_ID,index,index2
sample_A,ATCGACGT,GCTAGCTA
sample_B,ATXGACGT,GCTAGCTA
'''
    String msg = null
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } catch (IllegalArgumentException e) {
        msg = e.message
    } finally { tmp.delete() }
    assert msg != null, "expected an exception for the bad V2 index character"
    assert msg.contains('I7') && msg.contains('ATXG'),
        "expected the error to call out the bad V2 I7 sequence: ${msg}"
})

runner.test('V2 with a duplicate I7+I5 combination is caught by the bcl2fastq validator', {
    def tmp = File.createTempFile('v2_dup_index', '.csv')
    tmp.text = '''[Header]
FileFormatVersion,2

[BCLConvert_Data]
Sample_ID,index,index2
sample_A,ATCGACGT,GCTAGCTA
sample_B,ATCGACGT,GCTAGCTA
'''
    String msg = null
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } catch (IllegalArgumentException e) {
        msg = e.message
    } finally { tmp.delete() }
    assert msg != null, "expected an exception for duplicate V2 I7+I5"
    assert msg.contains('Duplicate index combination'),
        "expected the combination check to fire on V2: ${msg}"
    assert msg.contains('sample_A') && msg.contains('sample_B'),
        "expected both V2 colliding samples to be listed: ${msg}"
})

runner.test('V2 with a Sample_ID duplicate is caught by the bcl2fastq validator', {
    def tmp = File.createTempFile('v2_dup_sample', '.csv')
    tmp.text = '''[Header]
FileFormatVersion,2

[BCLConvert_Data]
Sample_ID,index,index2
sample_A,ATCGACGT,GCTAGCTA
sample_A,GGCTAACC,TGACCGAA
'''
    String msg = null
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } catch (IllegalArgumentException e) {
        msg = e.message
    } finally { tmp.delete() }
    assert msg != null, "expected an exception for duplicate V2 Sample_ID"
    assert msg.contains('Duplicate Sample_ID') && msg.contains('sample_A'),
        "expected the duplicate check to fire on V2: ${msg}"
})

runner.test('V2 with indices 1 base apart (Hamming distance violation) is caught by the validator', {
    // Verifies the new Hamming distance check works on V2 records,
    // not just V1. ATCG and ATCA differ by 1 base — the check
    // should flag this.
    def tmp = File.createTempFile('v2_hamming', '.csv')
    tmp.text = '''[Header]
FileFormatVersion,2

[BCLConvert_Data]
Sample_ID,index,index2
sample_A,ATCGACGT,GCTAGCTA
sample_B,ATCAACGT,GCTAGCTA
'''
    String msg = null
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } catch (IllegalArgumentException e) {
        msg = e.message
    } finally { tmp.delete() }
    assert msg != null, "expected an exception for V2 indices within Hamming distance 1"
    assert msg.contains('Hamming distance') && msg.contains('ATCGACGT') && msg.contains('ATCAACGT'),
        "expected the Hamming check to fire on V2: ${msg}"
})

runner.test('Quoted fields with commas and escaped quotes parse correctly', {
    def samples = SamplesheetReshape.parseSamplesheet(new File(dataDir, 'illumina_bcl2fastq_quoted.csv'))
    assert samples.size() == 2
    assert samples[0].Description == 'patient with, comma'
    assert samples[1].Description == 'escaped "quote" here'
})

runner.test('Throws on a non-existent file', {
    boolean threw = false
    try {
        SamplesheetReshape.parseSamplesheet(new File('does/not/exist.csv'))
    } catch (IllegalArgumentException e) {
        threw = true
    }
    assert threw, "expected IllegalArgumentException for missing file"
})

runner.test('Empty [Data] section yields zero samples, not an error', {
    def tmp = File.createTempFile('empty_data', '.csv')
    tmp.text = '[Data]\n'
    try {
        def samples = SamplesheetReshape.parseSamplesheet(tmp)
        assert samples.isEmpty(), "expected 0 samples, got ${samples.size()}"
    } finally {
        tmp.delete()
    }
})

runner.test('parseSamplesheet throws on a header row with an empty cell', {
    def tmp = File.createTempFile('bad_header', '.csv')
    tmp.text = '''[Data]
Sample_ID,,Sample_Well,index,index2,Sample_Project
sample_A,sample_A,,ATCG,GCTA,project_x,
'''
    boolean threw = false
    try {
        SamplesheetReshape.parseSamplesheet(tmp)
    } catch (IllegalArgumentException e) {
        threw = true
        assert e.message.toLowerCase().contains('empty'),
            "expected an empty-cell error, got: ${e.message}"
    } finally {
        tmp.delete()
    }
    assert threw, "expected IllegalArgumentException for empty header cell"
})

// -----------------------------------------------------------------------------
// malformed samplesheet handling
// -----------------------------------------------------------------------------
println "\n[malformed samplesheet handling]"

runner.test('parseSamplesheet throws on a truly empty file', {
    def tmp = File.createTempFile('empty', '.csv')
    try {
        boolean threw = false
        try {
            SamplesheetReshape.parseSamplesheet(tmp)
        } catch (IllegalArgumentException e) {
            threw = true
            assert e.message.toLowerCase().contains('empty'),
                "expected an empty-file error, got: ${e.message}"
        }
        assert threw, "expected IllegalArgumentException for empty file"
    } finally { tmp.delete() }
})

runner.test('parseSamplesheet throws on a whitespace-only file', {
    def tmp = File.createTempFile('whitespace', '.csv')
    tmp.text = '   \n  \n   \n'
    boolean threw = false
    try {
        SamplesheetReshape.parseSamplesheet(tmp)
    } catch (IllegalArgumentException e) {
        threw = true
        assert e.message.toLowerCase().contains('empty'),
            "expected an empty-file error, got: ${e.message}"
    } finally { tmp.delete() }
    assert threw, "expected IllegalArgumentException for whitespace-only file"
})

runner.test('parseSamplesheet throws when bcl2fastq has [Header]/[Reads] but no [Data]', {
    def tmp = File.createTempFile('nodata', '.csv')
    tmp.text = '''[Header]
IEMFileVersion,4

[Reads]
Read1Cycles,151

[Manifests]
'''
    boolean threw = false
    try {
        SamplesheetReshape.parseSamplesheet(tmp)
    } catch (IllegalArgumentException e) {
        threw = true
        assert e.message.toLowerCase().contains('[data]') || e.message.toLowerCase().contains('data section'),
            "expected a missing-[Data]-section error, got: ${e.message}"
    } finally { tmp.delete() }
    assert threw, "expected IllegalArgumentException for bcl2fastq without [Data]"
})

runner.test('parseSamplesheet throws on duplicate header column names', {
    def tmp = File.createTempFile('dup', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index,index2
sample_A,sample_A,ATCG,GCTA,GCTA
'''
    boolean threw = false
    try {
        SamplesheetReshape.parseSamplesheet(tmp)
    } catch (IllegalArgumentException e) {
        threw = true
        assert e.message.toLowerCase().contains('duplicate'),
            "expected a duplicate-column error, got: ${e.message}"
        assert e.message.contains('index'),
            "expected the duplicate column name in the message, got: ${e.message}"
    } finally { tmp.delete() }
    assert threw, "expected IllegalArgumentException for duplicate columns"
})

runner.test('parseSamplesheet throws when Sample_ID column is missing', {
    def tmp = File.createTempFile('nosampleid', '.csv')
    tmp.text = '''[Data]
sample_name,index,index2
sample_A,ATCG,GCTA
'''
    boolean threw = false
    try {
        SamplesheetReshape.parseSamplesheet(tmp)
    } catch (IllegalArgumentException e) {
        threw = true
        assert e.message.toLowerCase().contains('sample_id'),
            "expected a missing-Sample_ID error, got: ${e.message}"
    } finally { tmp.delete() }
    assert threw, "expected IllegalArgumentException for missing Sample_ID column"
})

runner.test('parseSamplesheet accepts lowercase sample_id column (case-insensitive)', {
    def tmp = File.createTempFile('lowercase', '.csv')
    tmp.text = '''[Data]
sample_id,index,index2
sample_A,ATCG,GCTA
'''
    try {
        def samples = SamplesheetReshape.parseSamplesheet(tmp)
        assert samples.size() == 1
    } finally { tmp.delete() }
})

runner.test('parseSamplesheet handles multi-line quoted fields', {
    def tmp = File.createTempFile('multiline', '.csv')
    // Description contains an embedded newline — must be treated as
    // part of the field, not a row terminator.
    tmp.text = '[Data]\n' +
        'Sample_ID,Sample_Name,index,index2,Description\n' +
        "sample_A,sample_A,ATCG,GCTA,\"line one\nline two\"\n" +
        'sample_B,sample_B,GGCC,TGAA,plain description\n'
    try {
        def samples = SamplesheetReshape.parseSamplesheet(tmp)
        assert samples.size() == 2,
            "expected 2 samples, got ${samples.size()} (multi-line field likely broke the row split)"
        assert samples[0].Sample_ID == 'sample_A'
        assert samples[0].Description == "line one\nline two",
            "multi-line field not preserved correctly: '${samples[0].Description}'"
        assert samples[1].Sample_ID == 'sample_B'
        assert samples[1].Description == 'plain description'
    } finally { tmp.delete() }
})

runner.test('parseSamplesheet handles CRLF line endings', {
    def tmp = File.createTempFile('crlf', '.csv')
    tmp.bytes = '[Data]\r\nSample_ID,index,index2\r\nsample_A,ATCG,GCTA\r\n'.bytes
    try {
        def samples = SamplesheetReshape.parseSamplesheet(tmp)
        assert samples.size() == 1
        assert samples[0].Sample_ID == 'sample_A'
    } finally { tmp.delete() }
})

runner.test('parseSamplesheet handles UTF-8 BOM at start of file', {
    def tmp = File.createTempFile('bom', '.csv')
    ByteArrayOutputStream combined = new ByteArrayOutputStream()
    combined.write([0xEF, 0xBB, 0xBF] as byte[])
    combined.write('[Data]\nSample_ID,index,index2\nsample_A,ATCG,GCTA\n'.bytes)
    tmp.bytes = combined.toByteArray()
    try {
        def samples = SamplesheetReshape.parseSamplesheet(tmp)
        assert samples.size() == 1,
            "expected 1 sample after BOM strip, got ${samples.size()}"
        assert samples[0].Sample_ID == 'sample_A',
            "BOM leaked into Sample_ID: '${samples[0].Sample_ID}'"
    } finally { tmp.delete() }
})

runner.test('parseSamplesheet reads file as UTF-8 (not platform default)', {
    def tmp = File.createTempFile('utf8', '.csv')
    tmp.bytes = ('[Data]\nSample_ID,Sample_Name,index,index2\n' +
                 'sample_A,样品,ATCG,GCTA\n').getBytes('UTF-8')
    try {
        def samples = SamplesheetReshape.parseSamplesheet(tmp)
        assert samples.size() == 1
        assert samples[0].Sample_Name == '样品',
            "non-ASCII Sample_Name not preserved: '${samples[0].Sample_Name}'"
    } finally { tmp.delete() }
})

runner.test('parseSamplesheet error messages include the file path for debugging', {
    def tmp = new File('/tmp/does-not-exist-' + System.nanoTime() + '.csv')
    try {
        String msg = null
        try {
            SamplesheetReshape.parseSamplesheet(tmp)
        } catch (IllegalArgumentException e) {
            msg = e.message
        }
        assert msg != null, "expected an exception for missing file"
        assert msg.contains(tmp.absolutePath),
            "error message should include the file path, got: ${msg}"
    } finally { tmp.delete() }
})

runner.test('validate(file) is a convenience that just calls parseSamplesheet', {
    def tmp = File.createTempFile('validate_ok', '.csv')
    tmp.text = '[Data]\nSample_ID,index,index2\nsample_A,ATCG,GCTA\n'
    try {
        // Good file → no exception
        SamplesheetReshape.validate(tmp)
        // Bad file → exception
        def bad = File.createTempFile('validate_bad', '.csv')
        bad.text = ''
        boolean threw = false
        try {
            SamplesheetReshape.validate(bad)
        } catch (IllegalArgumentException) {
            threw = true
        } finally { bad.delete() }
        assert threw, "validate should throw on a malformed samplesheet"
    } finally { tmp.delete() }
})

// -----------------------------------------------------------------------------
// parse-level errors raised when reshape hits bad rows
// (these are collected and reported in a single exception)
// -----------------------------------------------------------------------------
println "\n[parse-level errors raised through reshape]"

runner.test('a row with the wrong number of cells now throws (not warns-and-skips)', {
    def tmp = File.createTempFile('bad_row', '.csv')
    tmp.text = '''[Data]
Sample_ID,index,index2
sample_A,ATCG,GCTA
sample_B,GGCC
sample_C,AACC,CCAA
'''
    try {
        String msg = null
        try {
            SamplesheetReshape.parseSamplesheet(tmp)
        } catch (IllegalArgumentException e) {
            msg = e.message
        }
        assert msg != null, "expected an exception for a malformed row"
        assert msg.contains('line 3') || msg.contains('line 4'),
            "error should reference the bad line number: ${msg}"
        assert msg.contains('2 cells') || msg.contains('expected 3'),
            "error should explain the cell-count mismatch: ${msg}"
    } finally { tmp.delete() }
})

runner.test('a row with empty Sample_ID value now throws (not warns-and-skips)', {
    def tmp = File.createTempFile('empty_id', '.csv')
    tmp.text = '''[Data]
Sample_ID,index,index2
sample_A,ATCG,GCTA
,GGCC,TGAA
'''
    try {
        String msg = null
        try {
            SamplesheetReshape.parseSamplesheet(tmp)
        } catch (IllegalArgumentException e) {
            msg = e.message
        }
        assert msg != null, "expected an exception for an empty Sample_ID value"
        assert msg.toLowerCase().contains('sample_id'),
            "error should mention Sample_ID: ${msg}"
    } finally { tmp.delete() }
})

runner.test('parseSamplesheet collects ALL malformed rows into a single exception', {
    def tmp = File.createTempFile('multi_bad', '.csv')
    tmp.text = '''[Data]
Sample_ID,index,index2
sample_A,ATCG,GCTA
sample_B,GGCC
sample_C
sample_D,TTAA
sample_E,AACC,CCAA
'''
    try {
        String msg = null
        try {
            SamplesheetReshape.parseSamplesheet(tmp)
        } catch (IllegalArgumentException e) {
            msg = e.message
        }
        assert msg != null, "expected an exception"
        // 3 bad rows (B, C, D)
        assert msg.count('line ') >= 3,
            "expected at least 3 line references, got: ${msg}"
    } finally { tmp.delete() }
})

System.exit(runner.summary())
