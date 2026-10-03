// Tests for the `opts` Map options: `recursive` and `strandedness`.
// Also covers the opts-coercion footguns (Boolean true, "true", "false",
// empty string for strandedness) and the unknown-key forward-compat
// contract.
//
// Run with:  groovy -cp lib tests/test_options.groovy
//
// Exit code 0 on success, non-zero on failure.

import java.nio.file.Files

def ctx = evaluate(new File('tests/test_runner.groovy'))
def dataDir = ctx.dataDir
def fastqDir = ctx.fastqDir
def runner = ctx.runner

// -----------------------------------------------------------------------------
// recursive option
// -----------------------------------------------------------------------------
println "\n[recursive option]"

runner.test('recursive=false (default) ignores fastqs in subdirectories', {
    def root = Files.createTempDirectory('reshape_rec_').toFile()
    def nested = new File(root, 'Project_X/sample_A')
    nested.mkdirs()
    def topR1 = new File(root, 'sample_A_S1_L001_R1_001.fastq.gz')
    def topR2 = new File(root, 'sample_A_S1_L001_R2_001.fastq.gz')
    def nestedR1 = new File(nested, 'sample_A_S1_L002_R1_001.fastq.gz')
    def nestedR2 = new File(nested, 'sample_A_S1_L002_R2_001.fastq.gz')
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    [topR1, topR2, nestedR1, nestedR2].each { it.bytes = bytes }

    try {
        // Single-sample samplesheet so strict reshape doesn't throw on
        // samples that lack fastqs in this temp dir.
        def tmp = File.createTempFile('one_sample', '.csv')
        tmp.text = '''[Data]
Sample_ID,index,index2
sample_A,ATCG,GCTA
'''
        try {
            def csv = SamplesheetReshape.reshape(tmp, root)  // default recursive=false
            def sampleA = csv.readLines().drop(1).find { it.startsWith('sample_A,') }
            assert sampleA != null, "sample_A row missing"
            // Only the top-level L001 pair should match; the nested L002
            // pair must be ignored because we're not recursing.
            assert sampleA.contains('L001_R1'), "expected top-level L001_R1, got: ${sampleA}"
            assert !sampleA.contains('L002_R1'), "nested L002_R1 leaked into non-recursive result: ${sampleA}"
            assert !sampleA.contains('L002_R2'), "nested L002_R2 leaked into non-recursive result: ${sampleA}"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

runner.test('recursive=true discovers fastqs in subdirectories', {
    def root = Files.createTempDirectory('reshape_rec_').toFile()
    def nested = new File(root, 'Project_X/sample_A')
    nested.mkdirs()
    def topR1 = new File(root, 'sample_A_S1_L001_R1_001.fastq.gz')
    def topR2 = new File(root, 'sample_A_S1_L001_R2_001.fastq.gz')
    def nestedR1 = new File(nested, 'sample_A_S1_L002_R1_001.fastq.gz')
    def nestedR2 = new File(nested, 'sample_A_S1_L002_R2_001.fastq.gz')
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    [topR1, topR2, nestedR1, nestedR2].each { it.bytes = bytes }

    try {
        def tmp = File.createTempFile('one_sample', '.csv')
        tmp.text = '''[Data]
Sample_ID,index,index2
sample_A,ATCG,GCTA
'''
        try {
            def csv = SamplesheetReshape.reshape(tmp, root, [recursive: true])
            def sampleA = csv.readLines().drop(1).find { it.startsWith('sample_A,') }
            assert sampleA != null, "sample_A row missing"
            // Both lanes (top + nested) should be in the output
            assert sampleA.contains('L001_R1') && sampleA.contains('L002_R1'),
                "expected both R1 lanes (top + nested) with recursive=true, got: ${sampleA}"
            assert sampleA.contains('L001_R2') && sampleA.contains('L002_R2'),
                "expected both R2 lanes (top + nested) with recursive=true, got: ${sampleA}"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

runner.test('recursive=false (default) is the same as not passing opts', {
    def explicit = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir,
        [recursive: false]
    )
    def implicit = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir
    )
    assert explicit == implicit,
        "explicit recursive=false should match the default behaviour"
})

runner.test('opts.recursive is the only key currently recognised', {
    // Forward-compatibility: unknown keys should be ignored, not crash.
    def csv = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir,
        [recursive: false, futureOption: 'whatever', anotherOne: 42]
    )
    assert csv.readLines()[0] == 'sample,fastq_1,fastq_2'
})

// -----------------------------------------------------------------------------
// strandedness option
// -----------------------------------------------------------------------------
println "\n[strandedness option]"

runner.test('default (no strandedness) omits the strandedness column', {
    def csv = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir
    )
    def header = csv.readLines()[0]
    assert header == 'sample,fastq_1,fastq_2',
        "default header should be 3 columns, got: '${header}'"
    assert !csv.contains(',unstranded'),
        "default output should not contain any strandedness values"
})

runner.test('strandedness=unstranded adds a fourth column with that value for every sample', {
    def csv = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir,
        [strandedness: 'unstranded']
    )
    def lines = csv.readLines()
    assert lines[0] == 'sample,fastq_1,fastq_2,strandedness',
        "expected 4-column header, got: '${lines[0]}'"
    lines.drop(1).each { row ->
        assert row.endsWith(',unstranded'),
            "every row should end with ',unstranded', got: '${row}'"
    }
})

runner.test('strandedness works alongside recursive=true', {
    def root = Files.createTempDirectory('reshape_stranded_').toFile()
    def nested = new File(root, 'Project_X/sample_A')
    nested.mkdirs()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    new File(root, 'sample_A_S1_L001_R1_001.fastq.gz').bytes = bytes
    new File(nested, 'sample_A_S1_L001_R2_001.fastq.gz').bytes = bytes

    try {
        // Single-sample so strict reshape doesn't throw on the other
        // samples that lack fastqs in this temp fixture.
        def tmp = File.createTempFile('one_sample', '.csv')
        tmp.text = '''[Data]
Sample_ID,index,index2
sample_A,ATCG,GCTA
'''
        try {
            def csv = SamplesheetReshape.reshape(
                tmp,
                root,
                [recursive: true, strandedness: 'reverse']
            )
            def lines = csv.readLines()
            assert lines[0] == 'sample,fastq_1,fastq_2,strandedness'
            def sampleA = lines.drop(1).find { it.startsWith('sample_A,') }
            assert sampleA != null
            assert sampleA.endsWith(',reverse'),
                "sample_A row should end with ',reverse', got: '${sampleA}'"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

runner.test('strandedness null is the same as omitting it', {
    def explicit = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir,
        [strandedness: null]
    )
    def implicit = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir
    )
    assert explicit == implicit,
        "strandedness=null should match the default behaviour"
})

runner.test('strandedness with a single-end sample: fastq_2 is empty, strandedness still emitted', {
    def csv = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir,
        [strandedness: 'forward']
    )
    def sampleC = csv.readLines().drop(1).find { it.startsWith('sample_C,') }
    assert sampleC != null
    // Single-end: fastq_2 cell is empty, so the row ends ',,forward'
    // (the empty fastq_2 cell, then the strandedness value)
    assert sampleC.endsWith(',,forward'),
        "single-end with strandedness should end ',,forward', got: '${sampleC}'"
})

runner.test('strandedness=true (Boolean) is coerced to null — no column', {
    // The lib coerces a Boolean (which is what Nextflow's CLI gives
    // you when you pass `--strandedness` with no value, or
    // `--strandedness ''` which Nextflow collapses to the String "true")
    // back to null, so the footgun case yields the default 3-column
    // output instead of a column with the literal value "true".
    def csv = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir,
        [strandedness: true]
    )
    def lines = csv.readLines()
    assert lines[0] == 'sample,fastq_1,fastq_2',
        "Boolean strandedness should be coerced to null (no column), got: '${lines[0]}'"
    lines.drop(1).each { assert !it.contains(',true') }
})

runner.test('strandedness="" (empty string) is coerced to null — no column', {
    def csv = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir,
        [strandedness: '']
    )
    assert csv.readLines()[0] == 'sample,fastq_1,fastq_2'
})

runner.test('strandedness="true" (the Nextflow empty-string footgun) is coerced to null', {
    // Nextflow collapses `--strandedness ''` to the String "true".
    // The lib should not emit a column with the literal value "true".
    def csv = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir,
        [strandedness: 'true']
    )
    assert csv.readLines()[0] == 'sample,fastq_1,fastq_2',
        "strandedness='true' should be coerced to null, got: '${csv.readLines()[0]}'"
})

runner.test('strandedness="false" is also coerced to null', {
    def csv = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir,
        [strandedness: 'false']
    )
    assert csv.readLines()[0] == 'sample,fastq_1,fastq_2'
})

runner.test('named-arg sugar (strandedness: x) is equivalent to [strandedness: x] when overloads allow it', {
    // The named-arg form is a Groovy language feature that only works
    // when the call site unambiguously matches a single overload. With
    // our multiple overloads (File, Object, etc.), Groovy sometimes
    // binds the named-arg Map to the wrong position. So we only assert
    // the explicit-Map form here; that's the canonical way to pass opts.
    def withMap = SamplesheetReshape.reshape(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir,
        [strandedness: 'unstranded']
    )
    assert withMap.readLines()[0] == 'sample,fastq_1,fastq_2,strandedness'
})

System.exit(runner.summary())
