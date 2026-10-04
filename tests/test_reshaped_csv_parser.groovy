// Unit tests for ReshapedCsvParser — the RFC-4180 reader that
// sits behind SamplesheetReshape.parseReshapedCsv(). This is the
// canonical parser for the lib's output; the sub-workflow and
// every downstream consumer go through it.
//
// Run with:  groovy -cp lib tests/test_reshaped_csv_parser.groovy

import java.nio.file.Files

def ctx = evaluate(new File('tests/test_runner.groovy'))
def dataDir = ctx.dataDir
def fastqDir = ctx.fastqDir
def runner = ctx.runner

// Helper: write a CSV string to a temp file and parse it.
def parseString = { String csv ->
    def tmp = File.createTempFile('reshaped_csv_', '.csv')
    tmp.text = csv
    try {
        return SamplesheetReshape.parseReshapedCsv(tmp)
    } finally {
        tmp.delete()
    }
}

println "\n[ReshapedCsvParser — happy path]"

runner.test('parses a 2-row CSV (header + 1 record) into one map', {
    def records = parseString("""sample,fastq_1,fastq_2
sample_A,/path/A_R1.fq,/path/A_R2.fq
""")
    assert records.size() == 1
    assert records[0].meta.id == 'sample_A'
    assert records[0].meta.single_end == false
    assert records[0].fastq_1.size() == 1
    assert records[0].fastq_1[0].toString() == '/path/A_R1.fq'
    assert records[0].fastq_2.size() == 1
    assert records[0].fastq_2[0].toString() == '/path/A_R2.fq'
})

runner.test('parses a 4-row CSV and preserves order', {
    def records = parseString("""sample,fastq_1,fastq_2
sample_A,/A_R1.fq,/A_R2.fq
sample_B,/B_R1.fq,/B_R2.fq
sample_C,/C_R1.fq,
sample_D,/D_R1.fq,/D_R2.fq
""")
    assert records.size() == 4
    assert records*.meta.id == ['sample_A', 'sample_B', 'sample_C', 'sample_D']
    assert records[2].meta.single_end == true,
        "sample_C has empty fastq_2, so meta.single_end should be true"
    assert records[0].meta.single_end == false
    assert records[2].fastq_2.isEmpty()
})

println "\n[ReshapedCsvParser — RFC-4180 quoted multi-value cells]"

runner.test('preserves a quoted cell that contains a comma', {
    // The whole reason this parser exists: a multi-lane sample has
    // a comma-joined cell wrapped in double quotes.
    def records = parseString('''sample,fastq_1,fastq_2
sample_A,"/A_R1_lane1.fq,/A_R1_lane2.fq","/A_R2_lane1.fq,/A_R2_lane2.fq"
''')
    assert records.size() == 1
    assert records[0].fastq_1.size() == 2,
        "expected 2 paths in the fastq_1 cell, got: ${records[0].fastq_1}"
    assert records[0].fastq_1*.toString() == ['/A_R1_lane1.fq', '/A_R1_lane2.fq']
    assert records[0].fastq_2*.toString() == ['/A_R2_lane1.fq', '/A_R2_lane2.fq']
})

runner.test('leaves a single-value cell unquoted (no leading/trailing `"`)', {
    def records = parseString('''sample,fastq_1,fastq_2
sample_B,/B_R1.fq,/B_R2.fq
''')
    assert records[0].fastq_1[0].toString() == '/B_R1.fq'
    assert records[0].fastq_2[0].toString() == '/B_R2.fq'
})

runner.test('handles an escaped double-quote inside a quoted cell ("" → ")', {
    def records = parseString('''sample,fastq_1,fastq_2
sample_X,"/path/with""quote.fq",/path/normal.fq
''')
    assert records[0].fastq_1[0].toString() == '/path/with"quote.fq'
    assert records[0].fastq_2[0].toString() == '/path/normal.fq'
})

println "\n[ReshapedCsvParser — encoding and line endings]"

runner.test('strips UTF-8 BOM at start of file', {
    def tmp = File.createTempFile('reshaped_csv_bom_', '.csv')
    def bytes = ([0xEF, 0xBB, 0xBF] as byte[]) as List
    bytes += 'sample,fastq_1,fastq_2\nsample_A,/A.fq,/A_R2.fq\n'.bytes as List
    tmp.bytes = bytes as byte[]
    try {
        def records = SamplesheetReshape.parseReshapedCsv(tmp)
        assert records.size() == 1
        assert records[0].meta.id == 'sample_A',
            "BOM should not leak into the first column header, got: '${records[0].meta.id}'"
    } finally {
        tmp.delete()
    }
})

runner.test('handles CRLF line endings (Windows-style)', {
    def tmp = File.createTempFile('reshaped_csv_crlf_', '.csv')
    tmp.bytes = 'sample,fastq_1,fastq_2\r\nsample_A,/A.fq,/A_R2.fq\r\n'.bytes
    try {
        def records = SamplesheetReshape.parseReshapedCsv(tmp)
        assert records.size() == 1
        assert records[0].meta.id == 'sample_A'
    } finally {
        tmp.delete()
    }
})

println "\n[ReshapedCsvParser — empty / malformed input]"

runner.test('returns an empty list for a header-only file', {
    def records = parseString("sample,fastq_1,fastq_2\n")
    assert records.isEmpty(), "expected empty list (no data rows), got: ${records}"
})

runner.test('throws if the header is missing the required `sample` column', {
    String msg = null
    try {
        parseString("fastq_1,fastq_2\n/A.fq,/A_R2.fq\n")
    } catch (IllegalArgumentException e) {
        msg = e.message
    }
    assert msg != null, "expected IllegalArgumentException for missing 'sample' header"
    assert msg.contains("'sample'"),
        "error should mention the missing 'sample' column: ${msg}"
})

runner.test('throws on an unclosed quote', {
    String msg = null
    try {
        parseString('''sample,fastq_1,fastq_2
sample_A,"/unterminated.fq,/other.fq
''')
    } catch (IllegalArgumentException e) {
        msg = e.message
    }
    assert msg != null, "expected IllegalArgumentException for unclosed quote"
    assert msg.contains("Unclosed quote"),
        "error should mention the unclosed quote: ${msg}"
})

println "\n[ReshapedCsvParser — end-to-end with the lib's own output]"

runner.test('round-trips the CSV emitted by SamplesheetReshape.writeReshaped', {
    // The integration test: take the actual CSV the lib writes, run
    // it through the parser, and verify each record has the right
    // shape. This is what the sub-workflow does in production.
    def outDir = new File(fastqDir.parentFile, 'reshaped_csv_parser_test_out')
    if (outDir.exists()) outDir.deleteDir()
    outDir.mkdirs()
    try {
        def csvFile = SamplesheetReshape.writeReshaped(
            outDir,
            new File(dataDir, 'illumina_bcl2fastq.csv'),
            fastqDir
        )
        def records = SamplesheetReshape.parseReshapedCsv(csvFile)
        assert records.size() == 4, "expected 4 sample rows, got: ${records.size()}"
        def sampleA = records.find { it.meta.id == 'sample_A' }
        assert sampleA != null, "sample_A row missing"
        // sample_A spans 2 lanes — the multi-value cells must
        // parse back as 2 files per direction.
        assert sampleA.fastq_1.size() == 2,
            "expected 2 paths in sample_A's fastq_1, got: ${sampleA.fastq_1}"
        assert sampleA.fastq_2.size() == 2,
            "expected 2 paths in sample_A's fastq_2, got: ${sampleA.fastq_2}"
        assert sampleA.meta.single_end == false
        // sample_C is single-end.
        def sampleC = records.find { it.meta.id == 'sample_C' }
        assert sampleC != null
        assert sampleC.meta.single_end == true
        assert sampleC.fastq_2.isEmpty()
        assert sampleC.fastq_1.size() == 1
        // Every file path parsed should point to an existing file.
        records.each { record ->
            record.fastq_1.each { f -> assert f.exists(), "missing: ${f}" }
            record.fastq_2.each { f -> assert f.exists(), "missing: ${f}" }
        }
    } finally {
        outDir.deleteDir()
    }
})

System.exit(runner.summary())
