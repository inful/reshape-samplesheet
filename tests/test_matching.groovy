// Tests for the sample-id → fastq-filename matching strategy: the
// default strict matching (prefix + separator), the substring
// footgun regression cases, and the `pattern` opt for fully custom
// regex templates.
//
// Run with:  groovy -cp lib tests/test_matching.groovy
//
// Exit code 0 on success, non-zero on failure.

import java.nio.file.Files

def ctx = evaluate(new File('tests/test_runner.groovy'))
def dataDir = ctx.dataDir
def fastqDir = ctx.fastqDir
def runner = ctx.runner

// -----------------------------------------------------------------------------
// default matching (strict: prefix + separator)
// -----------------------------------------------------------------------------
println "\n[default matching]"

runner.test('default matching: "sample_A" matches sample_A_S1_L001_R1_001.fastq.gz', {
    def root = Files.createTempDirectory('match_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    new File(root, 'sample_A_S1_L001_R1_001.fastq.gz').bytes = bytes
    new File(root, 'sample_A_S1_L001_R2_001.fastq.gz').bytes = bytes
    try {
        // Tiny samplesheet with one sample so we can isolate the match
        def tmp = File.createTempFile('one_sample', '.csv')
        tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCG,GCTA
'''
        try {
            def csv = SamplesheetReshape.reshape(tmp, root)
            def row = csv.readLines().drop(1).find { it.startsWith('sample_A,') }
            assert row != null, "sample_A row missing — matching failed: ${csv}"
            assert row.contains('L001_R1_001'), "expected the L001 fastq to match, got: ${row}"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

runner.test('default matching: a sample named "A" does NOT match "ABC_S1_L001_R1_001.fastq.gz"', {
    def root = Files.createTempDirectory('match_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    // Decoy: would false-match with the old substring behavior
    new File(root, 'ABC_S1_L001_R1_001.fastq.gz').bytes = bytes
    // Real match: gives the test a successful reshape so we can verify
    // the ABC file is NOT in the output
    new File(root, 'A_S1_L001_R1_001.fastq.gz').bytes = bytes
    new File(root, 'A_S1_L001_R2_001.fastq.gz').bytes = bytes
    try {
        def tmp = File.createTempFile('short_sample', '.csv')
        tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
A,A,ATCG,GCTA
'''
        try {
            def csv = SamplesheetReshape.reshape(tmp, root)
            def row = csv.readLines().drop(1).find { it.startsWith('A,') }
            assert row != null, "expected A to match A_..., got: ${csv}"
            // The ABC decoy should NOT be in the row
            assert !row.contains('ABC_'),
                "ABC_ should not match A, got: ${row}"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

runner.test('default matching: a sample does not match files in a parent dir named with the same prefix', {
    def root = Files.createTempDirectory('match_').toFile()
    def nested = new File(root, 'patient_A_S1')
    nested.mkdirs()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    // Top-level matching files (so reshape succeeds with the strict mode)
    new File(root, 'sample_A_S1_L001_R1_001.fastq.gz').bytes = bytes
    new File(root, 'sample_A_S1_L001_R2_001.fastq.gz').bytes = bytes
    // Nested decoy (would false-match with old substring behavior — but we
    // also test that non-recursive mode ignores it entirely).
    new File(nested, 'sample_A_S1_L001_R1_001.fastq.gz').bytes = bytes
    new File(nested, 'sample_A_S1_L001_R2_001.fastq.gz').bytes = bytes
    try {
        def tmp = File.createTempFile('one_sample', '.csv')
        tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCG,GCTA
'''
        try {
            // Non-recursive, nested dir is irrelevant
            def csv = SamplesheetReshape.reshape(tmp, root, [recursive: false])
            def row = csv.readLines().drop(1).find { it.startsWith('sample_A,') }
            assert row != null, "sample_A row missing"
            // The nested path should not appear in the output
            assert !row.contains('patient_A_S1/'),
                "nested path leaked into non-recursive result: ${row}"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

runner.test('default matching: filename starting with sampleId followed by a "." separator matches', {
    def root = Files.createTempDirectory('match_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    new File(root, 'sample_A.fastq.gz').bytes = bytes
    try {
        def tmp = File.createTempFile('one_sample', '.csv')
        tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCG,GCTA
'''
        try {
            def csv = SamplesheetReshape.reshape(tmp, root)
            def row = csv.readLines().drop(1).find { it.startsWith('sample_A,') }
            assert row != null, "expected sample_A.fastq.gz to match, got: ${csv}"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

runner.test('default matching: filename "sample_A1_..." does NOT match sample "sample_A"', {
    def root = Files.createTempDirectory('match_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    // Decoy: "1" right after "sample_A" is not a separator
    new File(root, 'sample_A1_S1_L001_R1_001.fastq.gz').bytes = bytes
    // Real match: so the strict reshape succeeds and we can verify the
    // decoy is NOT in the output
    new File(root, 'sample_A_S1_L001_R1_001.fastq.gz').bytes = bytes
    new File(root, 'sample_A_S1_L001_R2_001.fastq.gz').bytes = bytes
    try {
        def tmp = File.createTempFile('one_sample', '.csv')
        tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCG,GCTA
'''
        try {
            def csv = SamplesheetReshape.reshape(tmp, root)
            def row = csv.readLines().drop(1).find { it.startsWith('sample_A,') }
            assert row != null, "sample_A row missing"
            // The sample_A1 decoy should NOT appear in the row
            assert !row.contains('sample_A1'),
                "sample_A1_ should not match sample_A, got: ${row}"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

// -----------------------------------------------------------------------------
// pattern option
// -----------------------------------------------------------------------------
println "\n[pattern option]"

runner.test('pattern option: full bcl2fastq regex rejects non-conforming filenames', {
    def root = Files.createTempDirectory('match_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    // A non-bcl2fastq filename that the default would accept
    new File(root, 'sample_A.fastq.gz').bytes = bytes
    // A bcl2fastq filename that the strict pattern accepts
    new File(root, 'sample_A_S1_L001_R1_001.fastq.gz').bytes = bytes
    new File(root, 'sample_A_S1_L001_R2_001.fastq.gz').bytes = bytes
    try {
        def tmp = File.createTempFile('one_sample', '.csv')
        tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCG,GCTA
'''
        try {
            def bcl2fastq = '^${sampleId}_S\\d+_L\\d+_R[12]_\\d+\\.fastq\\.gz$'
            def csv = SamplesheetReshape.reshape(tmp, root, [pattern: bcl2fastq])
            def row = csv.readLines().drop(1).find { it.startsWith('sample_A,') }
            assert row != null, "expected sample_A to match bcl2fastq files, got: ${csv}"
            assert row.contains('S1_L001_R1_001'),
                "expected the bcl2fastq-style file to match, got: ${row}"
            // The simple `sample_A.fastq.gz` should NOT match with the strict pattern
            assert !row.contains(',sample_A.fastq.gz,'),
                "non-bcl2fastq file should not match with the strict pattern: ${row}"
            assert !row.contains('sample_A.fastq.gz,') ||
                   row.count('sample_A.fastq.gz') == 0,
                "the non-conforming file should not appear in the row: ${row}"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

runner.test('pattern option: ${sampleId} is interpolated and the sampleId is regex-escaped', {
    // Sample_ID contains a regex metacharacter ("."). The lib must
    // escape it so it matches literally, not "any character".
    def root = Files.createTempDirectory('match_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    new File(root, 'v1.0_S1_L001_R1_001.fastq.gz').bytes = bytes
    // A decoy that would match if the "." in v1.0 was treated as a regex
    // wildcard (it would match "v1X0_..." for any X)
    new File(root, 'v1X0_S1_L001_R1_001.fastq.gz').bytes = bytes
    try {
        def tmp = File.createTempFile('dot_sample', '.csv')
        tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
v1.0,v1.0,ATCG,GCTA
'''
        try {
            def pattern = '^${sampleId}_S\\d+_L\\d+_R[12]_\\d+\\.fastq\\.gz$'
            def csv = SamplesheetReshape.reshape(tmp, root, [pattern: pattern])
            def row = csv.readLines().drop(1).find { it.startsWith('v1.0,') }
            assert row != null, "expected v1.0 to match v1.0_S1_..., got: ${csv}"
            assert row.contains('v1.0_S1_L001_R1_001'),
                "expected the v1.0_ file to match (literal dot), got: ${row}"
            assert !row.contains('v1X0'),
                "the decoy v1X0_ should not match (the dot was treated literally, not as regex), got: ${row}"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

runner.test('pattern option: ${sampleId} may be used multiple times in one pattern', {
    // Some users have filenames that embed the sample id twice
    def root = Files.createTempDirectory('match_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    new File(root, 'sample_A_v1_sample_A_R1.fastq.gz').bytes = bytes
    try {
        def tmp = File.createTempFile('one_sample', '.csv')
        tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCG,GCTA
'''
        try {
            def pattern = '${sampleId}_v1_${sampleId}_R[12]\\.fastq\\.gz$'
            def csv = SamplesheetReshape.reshape(tmp, root, [pattern: pattern])
            def row = csv.readLines().drop(1).find { it.startsWith('sample_A,') }
            assert row != null, "expected pattern with \${sampleId} twice to work: ${csv}"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

System.exit(runner.summary())
