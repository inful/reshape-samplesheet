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
