// Unit tests for lib/CsvCell.groovy — the RFC-4180 cell-level
// operations shared between the input parser (SamplesheetParser),
// the output parser (ReshapedCsvParser), and the writer
// (SamplesheetReshaper). All three callers route through this class,
// so a behaviour regression here would show up in tests for all
// three sites.
//
// Run with:  groovy -cp lib tests/test_csv_cell.groovy
//
// Exit code 0 on success, non-zero on failure.

def ctx = evaluate(new File('tests/test_runner.groovy'))
def runner = ctx.runner

// -----------------------------------------------------------------------------
// CsvCell.quote — RFC-4180 cell-level quoting
// -----------------------------------------------------------------------------
println "\n[CsvCell.quote]"

runner.test('quote(null) returns an empty cell', {
    assert CsvCell.quote(null) == '',
        "a null cell should be represented as an empty unquoted value"
})

runner.test('quote(empty) returns an empty cell (no quoting needed)', {
    assert CsvCell.quote('') == '',
        "an empty cell should not be wrapped in double quotes"
})

runner.test('quote(plain) returns the value verbatim (no quoting needed)', {
    assert CsvCell.quote('sample_A') == 'sample_A',
        "values without special characters must NOT be quoted (readability + nf-core convention)"
})

runner.test('quote(value with comma) wraps in double quotes', {
    assert CsvCell.quote('study_X,patient_Y') == '"study_X,patient_Y"',
        "values containing the separator must be wrapped"
})

runner.test('quote(value with embedded double quote) wraps and doubles the embedded quote', {
    // Per RFC 4180, an embedded " is escaped by doubling: "".
    assert CsvCell.quote('abc"def') == '"abc""def"',
        "embedded \" must be doubled per RFC 4180"
})

runner.test('quote(value with multiple embedded quotes) doubles every one', {
    // Two embedded quotes and a separator:
    // input:  a"b"c,d
    // output: "a""b""c,d"
    assert CsvCell.quote('a"b"c,d') == '"a""b""c,d"'
})

runner.test('quote(value with embedded LF) wraps in double quotes', {
    // A newline in a cell must round-trip through the writer/parser
    // as a single cell. The writer quotes so the parser's line
    // splitter treats the embedded \n as content, not a row
    // terminator.
    assert CsvCell.quote("line one\nline two") == "\"line one\nline two\""
})

runner.test('quote(value with embedded CR) wraps in double quotes', {
    // CRLF line endings on Windows could leave a stray \r at end of
    // cell — quote so the parser doesn't choke.
    assert CsvCell.quote("line\r\nend") == "\"line\r\nend\""
})

runner.test('quote(value with only `\n` boundary-style characters) wraps', {
    // Edge cases the writer used to miss:
    assert CsvCell.quote('\n') == '"\n"'
    assert CsvCell.quote('\r') == '"\r"'
})

runner.test('quote never leaves whitespace outside the value when quoting', {
    // Trailing/leading whitespace, when not in a special char, is
    // passed through unchanged (no padding the quoted region).
    assert CsvCell.quote('  spaced  ') == '  spaced  ',
        "whitespace-only values should NOT be quoted (no char to escape)"
})

// -----------------------------------------------------------------------------
// CsvCell.parseLine — RFC-4180 line-level cell parsing
// -----------------------------------------------------------------------------
println "\n[CsvCell.parseLine]"

runner.test('parseLine parses a single-cell line', {
    def cells = CsvCell.parseLine('sample_A')
    assert cells.size() == 1
    assert cells[0] == 'sample_A'
})

runner.test('parseLine parses three simple cells', {
    def cells = CsvCell.parseLine('a,b,c')
    assert cells == ['a', 'b', 'c']
})

runner.test('parseLine parses empty trailing cells (single-end row)', {
    // A row ending in `,` produces one extra empty cell — this is
    // what makes the single-end case (empty fastq_2) round-trippable
    // through ReshapedCsvParser.
    def cells = CsvCell.parseLine('sample_C,/r1,')
    assert cells == ['sample_C', '/r1', '']
})

runner.test('parseLine parses a quoted cell that contains the separator', {
    def cells = CsvCell.parseLine('"a,b",c')
    assert cells == ['a,b', 'c']
})

runner.test('parseLine parses an escaped double-quote ("" → ")', {
    def cells = CsvCell.parseLine('"a""b",c')
    assert cells == ['a"b', 'c']
})

runner.test('parseLine recovers from a stray mid-cell quote (literal)', {
    // A `"` that is not at the start of a cell is treated as a
    // literal character — this recovers gracefully from a file
    // whose quoting is malformed (rather than throwing on the first
    // stray quote).
    def cells = CsvCell.parseLine('foo"bar,baz')
    assert cells == ['foo"bar', 'baz'],
        "a stray mid-cell quote should be treated as a literal; got: ${cells}"
})

runner.test('parseLine throws IllegalArgumentException on an unclosed quote', {
    boolean threw = false
    String msg = null
    try {
        CsvCell.parseLine('"unterminated,/foo')
    } catch (IllegalArgumentException e) {
        threw = true
        msg = e.message
    }
    assert threw, "expected IllegalArgumentException for an unclosed quote"
    assert msg != null && msg.contains('Unclosed quote'),
        "error message should mention the unclosed quote: ${msg}"
})

runner.test('parseLine error message references the line number when provided', {
    String msg = null
    try {
        CsvCell.parseLine('"unterminated', 42)
    } catch (IllegalArgumentException e) {
        msg = e.message
    }
    assert msg != null
    assert msg.contains('line 42'),
        "error should reference the supplied line number, got: ${msg}"
})

runner.test("parseLine error message omits the line number when not provided",
            {
    String msg = null
    try {
        CsvCell.parseLine('"unterminated')
    } catch (IllegalArgumentException e) {
        msg = e.message
    }
    assert msg != null
    assert !msg.contains('line -1'),
        "error must NOT include '-1' as a line number, got: ${msg}"
    assert !msg.contains('line '),
        "error should be generic (no line number) when none was provided, got: ${msg}"
})

runner.test('parseLine on an empty line returns one empty cell', {
    def cells = CsvCell.parseLine('')
    assert cells.size() == 1
    assert cells[0] == ''
})

runner.test('parseLine preserves leading/trailing whitespace in cells', {
    // Trimming is the caller's responsibility (SamplesheetParser
    // does it explicitly via `.collect { it.trim() }`); the parser
    // itself just preserves what was there.
    def cells = CsvCell.parseLine(' a , b , c ')
    assert cells == [' a ', ' b ', ' c ']
})

// -----------------------------------------------------------------------------
// round-trip — the load-bearing invariant
// -----------------------------------------------------------------------------
println "\n[CsvCell round-trip]"

runner.test('round-trip: quote then parseLine yields the original', {
    [
        'sample_A',
        '',
        'plain text',
        'comma,inside',
        '"start with quote',
        'end with quote"',
        '"both "quotes" and commas"',
        'with"quote,comma',
        'two\nlines',
        'two\r\nlines',
        ' '
    ].each { value ->
        String quoted = CsvCell.quote(value)
        def cells = CsvCell.parseLine(quoted)
        assert cells.size() == 1,
            "round-trip produced more than one cell for value '${value}': ${cells}"
        assert cells[0] == value,
            "round-trip did not preserve value:\n  in:  '${value}'\n  out: '${cells[0]}'"
    }
})

runner.test('round-trip: parseLine then quote yields the same CSV', {
    // The other direction — start with a CSV line, parse it, quote
    // each cell back to CSV — should be a no-op for already-
    // normalised CSV (no embedded " or ,).
    String csv = 'sample_A,/r1.fq,/r2.fq'
    def cells = CsvCell.parseLine(csv)
    String reserialised = cells.collect { CsvCell.quote(it) }.join(',')
    assert reserialised == csv,
        "round-trip reserialisation should be a no-op, got: ${reserialised}"
})

runner.test('round-trip: complex quoted CSV is preserved', {
    // The kind of multi-value-cell CSV the writer produces in
    // production. Pre-refactor, this would have been read back
    // correctly; the contract test is that re-quoting it yields the
    // same bytes.
    String csv = 'sample_A,"/r1_l1.fq,/r1_l2.fq","/r2_l1.fq,/r2_l2.fq"'
    def cells = CsvCell.parseLine(csv)
    assert cells == [
        'sample_A',
        '/r1_l1.fq,/r1_l2.fq',
        '/r2_l1.fq,/r2_l2.fq'
    ]
    String reserialised = cells.collect { CsvCell.quote(it) }.join(',')
    assert reserialised == csv,
        "complex quoted CSV should round-trip unchanged; got: ${reserialised}"
})

System.exit(runner.summary())
