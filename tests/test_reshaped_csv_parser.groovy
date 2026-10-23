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

runner.test('handles a multi-line quoted cell (RFC-4180)', {
    // A quoted cell that contains a newline. The pre-fix parser
    // used text.split(/\r\n|\r|\n/) which silently broke here —
    // the cell got split into two records. The new CsvLines-based
    // splitter treats the embedded \n as cell content.
    def records = parseString('''sample,fastq_1,fastq_2
sample_A,"/path/with
embedded_newline.fq",/other.fq
''')
    assert records.size() == 1, "expected 1 record, got: ${records.size()}"
    assert records[0].meta.id == 'sample_A'
    assert records[0].fastq_1.size() == 1
    assert records[0].fastq_1[0].toString() == '/path/with\nembedded_newline.fq'
    assert records[0].fastq_2.size() == 1
    assert records[0].fastq_2[0].toString() == '/other.fq'
})

runner.test('multi-line quoted cell is preserved as a single record', {
    // A multi-line cell that includes a comma on the second line.
    // The parser keeps the entire content (newlines and embedded
    // commas) inside the one cell. The cell is then split on `,`
    // to recover per-lane file paths — and since real file paths
    // don't contain commas, this is fine. The test documents the
    // current behaviour and the load-bearing invariant that
    // multi-line cells produce a single record (not multiple).
    def records = parseString('''sample,fastq_1,fastq_2
sample_A,"/lane1.fq
/lane2.fq",/R2.fq
''')
    assert records.size() == 1, "expected 1 record, got: ${records.size()}"
    assert records[0].meta.id == 'sample_A'
    // The cell's two paths survive the multi-line split (note the
    // embedded \n is literal content; the .toString() shows it).
    assert records[0].fastq_1[0].toString() == '/lane1.fq\n/lane2.fq'
    assert records[0].fastq_2.size() == 1
    assert records[0].fastq_2[0].toString() == '/R2.fq'
})

runner.test('unclosed-quote error message includes the source line number', {
    String msg = null
    try {
        parseString('''sample,fastq_1,fastq_2
sample_B,/R1.fq,/R2.fq
sample_A,"/unterminated.fq
sample_C,/R1.fq,/R2.fq
''')
    } catch (IllegalArgumentException e) {
        msg = e.message
    }
    assert msg != null
    // The unclosed quote is on the 3rd non-empty line (after the
    // header on line 1 and sample_B on line 2).
    assert msg.contains("line 3") || msg.contains("line 4") || msg.contains("line 5"),
        "error should include a source line number, got: ${msg}"
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

// -----------------------------------------------------------------------------
// round-trip with pathological Sample_IDs — the regression lock for
// the pre-refactor behaviour where the writer quoted multi-value cells
// but NOT the sample-id cell, so a comma in the Sample_ID would
// silently mangle the round-tripped record (meta id truncated at the
// separator). Now that both halves of the round-trip (writer and
// parser) share the same RFC-4180 primitives in CsvCell, the contract
// is locked in one place.
// -----------------------------------------------------------------------------
println "\n[ReshapedCsvParser — round-trip with pathological Sample_IDs]"

runner.test('round-trips a CSV whose Sample_ID contains the separator', {
    // This is the exact shape of the output that the writer produces
    // for a Sample_ID like "study_X,patient_Y" — the Sample_ID cell is
    // quoted because it contains the separator.
    def tmp = File.createTempFile('reshaped_csv_comma_id_', '.csv')
    tmp.text = '''sample,fastq_1,fastq_2
"study_X,patient_Y","/path/to/A_R1.fq","/path/to/A_R2.fq"
'''
    try {
        def records = SamplesheetReshape.parseReshapedCsv(tmp)
        assert records.size() == 1, "expected 1 record, got: ${records.size()}"
        // The pre-refactor behaviour would have produced
        // meta.id = "study_X" and a misaligned fastq_2 = "patient_Y"",
        // because the parser would have split the unquoted cell on the
        // embedded comma. With RFC-4180 quoting, the embedded comma is
        // safely inside the cell and meta.id is preserved.
        assert records[0].meta.id == 'study_X,patient_Y',
            "meta.id should preserve the full Sample_ID with the embedded comma, got: '${records[0].meta.id}'"
        assert records[0].fastq_1.size() == 1
        assert records[0].fastq_2.size() == 1
    } finally {
        tmp.delete()
    }
})

runner.test('round-trips a CSV whose Sample_ID contains an embedded double-quote', {
    // A Sample_ID containing `"` is far less common than one with `,`,
    // but the writer must escape it the same way for the parser to
    // recover it cleanly.
    def tmp = File.createTempFile('reshaped_csv_quote_id_', '.csv')
    tmp.text = 'sample,fastq_1,fastq_2\n' +
               '"sample""with""quote",/path/R1.fq,/path/R2.fq\n'
    try {
        def records = SamplesheetReshape.parseReshapedCsv(tmp)
        assert records.size() == 1
        // The doubled "" inside the quoted cell decodes to a single "
        // on the way out.
        assert records[0].meta.id == 'sample"with"quote',
            "embedded \"\" should decode to a single literal \", got: '${records[0].meta.id}'"
    } finally {
        tmp.delete()
    }
})

// -----------------------------------------------------------------------------
// The strandedness column
//
// The `samples` emit is built by re-parsing the CSV, so a column the
// parser ignores is a column the consumer never sees. The writer
// emits a strandedness column when the caller asked for one, and the
// parser must carry it into meta.
// -----------------------------------------------------------------------------
println "\n[ReshapedCsvParser — strandedness column]"

runner.test('carries the strandedness column into meta', {
    def records = parseString("""sample,fastq_1,fastq_2,strandedness
sample_A,/A_R1.fq,/A_R2.fq,reverse
sample_B,/B_R1.fq,/B_R2.fq,forward
""")
    assert records.size() == 2
    assert records[0].meta.strandedness == 'reverse',
        "expected meta.strandedness == 'reverse', got: '${records[0].meta.strandedness}'"
    assert records[1].meta.strandedness == 'forward'
})

runner.test('leaves meta.strandedness unset when the CSV has no strandedness column', {
    def records = parseString("""sample,fastq_1,fastq_2
sample_A,/A_R1.fq,/A_R2.fq
""")
    assert records.size() == 1
    assert !records[0].meta.containsKey('strandedness'),
        "a CSV without the column must not invent one: ${records[0].meta}"
    assert records[0].meta.strandedness == null
})

System.exit(runner.summary())
