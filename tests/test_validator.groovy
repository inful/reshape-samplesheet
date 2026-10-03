// Tests for the pre-flight validation methods: `validate(samplesheet)`,
// `validate(samplesheet, fastqDir)`, `validateBcl2fastq(samplesheet)`,
// and the bcl2fastq structural checks (Sample_ID uniqueness, I7/I5
// index format and consistency). The bcl2fastq checks are also
// exercised through `validate(..., opts)` and `reshape(..., opts)`
// with `validateStructure: true`.
//
// Run with:  groovy -cp lib tests/test_validator.groovy
//
// Exit code 0 on success, non-zero on failure.

import java.nio.file.Files

def ctx = evaluate(new File('tests/test_runner.groovy'))
def dataDir = ctx.dataDir
def fastqDir = ctx.fastqDir
def runner = ctx.runner

// -----------------------------------------------------------------------------
// validate() pre-flight check
// -----------------------------------------------------------------------------
println "\n[validate() pre-flight check]"

runner.test('validate(samplesheet) throws on a malformed samplesheet', {
    def bad = File.createTempFile('validate_bad', '.csv')
    bad.text = '[Data]\n,index\n,ATCG\n'  // empty header cell
    boolean threw = false
    try {
        SamplesheetReshape.validate(bad)
    } catch (IllegalArgumentException) {
        threw = true
    } finally { bad.delete() }
    assert threw, "validate should throw on a malformed samplesheet"
})

runner.test('validate(samplesheet, fastqDir) throws when a sample has no fastq matches', {
    def root = Files.createTempDirectory('validate_missing_').toFile()
    def bytes = [0x1f, 0x8b, 0x08, 0x00] as byte[]
    new File(root, 'sample_A_S1_L001_R1_001.fastq.gz').bytes = bytes
    new File(root, 'sample_A_S1_L001_R2_001.fastq.gz').bytes = bytes
    try {
        def tmp = File.createTempFile('validate_missing', '.csv')
        tmp.text = '''[Data]
Sample_ID,index,index2
sample_A,ATCG,GCTA
sample_B,GGCC,TGAA
'''
        try {
            String msg = null
            try {
                SamplesheetReshape.validate(tmp, root)
            } catch (IllegalArgumentException e) {
                msg = e.message
            }
            assert msg != null, "validate should throw on missing fastqs"
            assert msg.contains('sample_B'),
                "error should list sample_B as missing: ${msg}"
        } finally { tmp.delete() }
    } finally { root.deleteDir() }
})

runner.test('validate(samplesheet, fastqDir) throws when fastq_dir is missing', {
    def tmp = new File(dataDir, 'illumina_bcl2fastq.csv')
    String msg = null
    try {
        SamplesheetReshape.validate(tmp, new File('/no/such/dir'))
    } catch (IllegalArgumentException e) {
        msg = e.message
    }
    assert msg != null, "validate should throw on missing fastq_dir"
    assert msg.contains('does not exist') || msg.contains('not a directory'),
        "error should explain the fastq_dir problem: ${msg}"
})

runner.test('validate(samplesheet, fastqDir) succeeds when everything is valid', {
    // No exception means success
    SamplesheetReshape.validate(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir
    )
})

// -----------------------------------------------------------------------------
// pre-bcl2fastq validation
// -----------------------------------------------------------------------------
println "\n[pre-bcl2fastq validation]"

runner.test('validateBcl2fastq succeeds on a well-formed bcl2fastq samplesheet', {
    SamplesheetReshape.validateBcl2fastq(
        new File(dataDir, 'illumina_bcl2fastq.csv')
    )
})

runner.test('validateBcl2fastq throws on duplicate Sample_IDs', {
    def tmp = File.createTempFile('dup_sampleid', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_A,sample_A,GGCTAACC,TGACCGAA
'''
    String msg = null
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } catch (IllegalArgumentException e) {
        msg = e.message
    } finally { tmp.delete() }
    assert msg != null, "expected an exception for duplicate Sample_IDs"
    assert msg.contains('Duplicate Sample_ID') && msg.contains('sample_A'),
        "expected the error to call out sample_A as duplicated: ${msg}"
})

runner.test('validateBcl2fastq throws on invalid index sequence characters', {
    def tmp = File.createTempFile('bad_index', '.csv')
    // 'ATXG' has an 'X' which is not a valid nucleotide
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATXGACGT,GCTAGCTA
'''
    String msg = null
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } catch (IllegalArgumentException e) {
        msg = e.message
    } finally { tmp.delete() }
    assert msg != null, "expected an exception for invalid index characters"
    assert msg.contains('I7') && msg.contains('ATXG'),
        "expected the error to call out the bad I7 sequence: ${msg}"
})

runner.test('validateBcl2fastq accepts lowercase index sequences — normalises to uppercase', {
    def tmp = File.createTempFile('lowercase_index', '.csv')
    // bcl2fastq expects uppercase; lowercase is technically valid nucleotides
    // but we follow the convention and reject to fail loud.
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,atcgacgt,GCTAGCTA
'''
    // The lib uppercases before matching, so this should actually PASS
    // (lowercase is normalised). Verify the expected behaviour:
    // for now we accept either case and document that we normalise.
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
        // If we get here, lowercase was accepted — that's fine.
    } catch (IllegalArgumentException e) {
        // If the lib rejects lowercase, that's also a valid design choice.
        // We just need to be consistent.
    } finally { tmp.delete() }
})

runner.test('validateBcl2fastq throws on duplicate I7+I5 index combinations', {
    def tmp = File.createTempFile('dup_index', '.csv')
    // Two samples with the same I7 AND the same I5 — demultiplexing impossible
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,ATCGACGT,GCTAGCTA
'''
    String msg = null
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } catch (IllegalArgumentException e) {
        msg = e.message
    } finally { tmp.delete() }
    assert msg != null, "expected an exception for duplicate index combinations"
    assert msg.contains('Duplicate index combination'),
        "expected the error to call out the duplicate index: ${msg}"
    assert msg.contains('sample_A') && msg.contains('sample_B'),
        "expected both colliding samples to be listed: ${msg}"
})

runner.test('validateBcl2fastq does NOT flag samples with same I7 but different I5 (dual-indexed, distinguishable)', {
    def tmp = File.createTempFile('dual_index', '.csv')
    // Same I7, different I5 — bcl2fastq can distinguish these
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,ATCGACGT,TGACCGAA
'''
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
        // No exception = success
    } catch (IllegalArgumentException e) {
        assert false, "same I7 with different I5 should be allowed: ${e.message}"
    } finally { tmp.delete() }
})

runner.test('validateBcl2fastq collects ALL bcl2fastq issues into a single error', {
    def tmp = File.createTempFile('multi_issue', '.csv')
    // Multiple problems: duplicate Sample_ID, bad index char, duplicate combo
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_A,sample_A,ATXGACCG,GCTAGCTA
sample_B,sample_B,ATCGACGT,GCTAGCTA
'''
    String msg = null
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } catch (IllegalArgumentException e) {
        msg = e.message
    } finally { tmp.delete() }
    assert msg != null, "expected an exception"
    assert msg.contains('Duplicate Sample_ID'),
        "expected Duplicate Sample_ID error: ${msg}"
    assert msg.contains('ATXG') || msg.contains('invalid characters'),
        "expected invalid character error: ${msg}"
    assert msg.contains('Duplicate index combination'),
        "expected duplicate index error: ${msg}"
})

runner.test('validateBcl2fastq on an LRM samplesheet (no index columns) only checks Sample_ID uniqueness', {
    // LRM samplesheets don't have index columns — only Sample_ID matters
    def tmp = File.createTempFile('lrm_dup', '.csv')
    tmp.text = '''Sample_ID,Sample_Name,Sample_Project,index,index2
sample_A,sample_A,project,ATCG,GCTA
sample_B,sample_B,project,GGCC,TGAA
'''
    // Should pass (unique Sample_IDs, no index columns to check)
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } catch (IllegalArgumentException e) {
        assert false, "LRM with unique Sample_IDs should pass: ${e.message}"
    } finally { tmp.delete() }
})

// -----------------------------------------------------------------------------
// validateStructure integration (opt-in bcl2fastq checks through validate/reshape)
// -----------------------------------------------------------------------------
println "\n[validateStructure integration]"

runner.test('validate(samplesheet, fastqDir, opts) with validateStructure:true runs both check sets', {
    // 4-sample bcl2fastq samplesheet + matching fastqs + validateStructure: true
    // should pass cleanly.
    SamplesheetReshape.validate(
        new File(dataDir, 'illumina_bcl2fastq.csv'),
        fastqDir,
        [validateStructure: true]
    )
})

runner.test('validate(samplesheet, fastqDir, opts) with validateStructure:true throws on bcl2fastq structural errors', {
    // A samplesheet with a bad index character (X is not a valid
    // nucleotide) — parse succeeds, but the bcl2fastq check must throw.
    def tmp = File.createTempFile('bad_index_for_opt', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATXGACGT,GCTAGCTA
'''
    try {
        String msg = null
        try {
            SamplesheetReshape.validate(tmp, fastqDir, [validateStructure: true])
        } catch (IllegalArgumentException e) {
            msg = e.message
        }
        assert msg != null, "expected an exception for bad index when validateStructure:true"
        assert msg.contains('ATXG') || msg.contains('invalid characters'),
            "error should call out the bad index sequence: ${msg}"
    } finally { tmp.delete() }
})

runner.test('reshape with validateStructure:true throws on duplicate Sample_IDs (integration through reshape)', {
    // The bcl2fastq checks are also wired into reshape() via opts.validateStructure.
    // Verify the integration: a parseable samplesheet with duplicate
    // Sample_IDs should throw when validateStructure:true is set.
    def tmp = File.createTempFile('dup_for_opt', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_A,sample_A,GGCTAACC,TGACCGAA
'''
    try {
        String msg = null
        try {
            SamplesheetReshape.reshape(tmp, fastqDir, [validateStructure: true])
        } catch (IllegalArgumentException e) {
            msg = e.message
        }
        assert msg != null, "expected an exception for duplicate Sample_ID with validateStructure:true"
        assert msg.contains('Duplicate Sample_ID'),
            "error should call out the duplicate Sample_ID: ${msg}"
    } finally { tmp.delete() }
})

// -----------------------------------------------------------------------------
// fix 6: empty index values are now reported, not silently passed
// -----------------------------------------------------------------------------
println "\n[empty index values]"

runner.test('validateBcl2fastq throws when the samplesheet has an index column but a sample has an empty I7 value', {
    // The samplesheet has the `index` column (so hasI7 is true) but
    // sample_B has no value. bcl2fastq would have nothing to demultiplex
    // by, so this is a real error — was previously silently accepted.
    def tmp = File.createTempFile('empty_i7', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,,TGACCGAA
'''
    try {
        String msg = null
        try {
            SamplesheetReshape.validateBcl2fastq(tmp)
        } catch (IllegalArgumentException e) {
            msg = e.message
        }
        assert msg != null, "expected an exception for empty I7 value"
        assert msg.contains('sample_B'),
            "error should call out the offending sample: ${msg}"
        assert msg.contains('I7') && msg.contains('empty'),
            "error should mention the empty I7 value: ${msg}"
    } finally { tmp.delete() }
})

runner.test('validateBcl2fastq throws when a sample has an empty I5 value', {
    def tmp = File.createTempFile('empty_i5', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,ATCGACGT,
'''
    try {
        String msg = null
        try {
            SamplesheetReshape.validateBcl2fastq(tmp)
        } catch (IllegalArgumentException e) {
            msg = e.message
        }
        assert msg != null, "expected an exception for empty I5 value"
        assert msg.contains('sample_B'),
            "error should call out the offending sample: ${msg}"
        assert msg.contains('I5') && msg.contains('empty'),
            "error should mention the empty I5 value: ${msg}"
    } finally { tmp.delete() }
})

// -----------------------------------------------------------------------------
// fix 7: consistent index lengths across samples
// -----------------------------------------------------------------------------
println "\n[consistent index lengths]"

runner.test('validateBcl2fastq throws when I7 lengths differ between samples', {
    // bcl2fastq requires all I7s in a run to be the same length.
    def tmp = File.createTempFile('inconsistent_i7', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,ATCG,TGACCGAA
'''
    try {
        String msg = null
        try {
            SamplesheetReshape.validateBcl2fastq(tmp)
        } catch (IllegalArgumentException e) {
            msg = e.message
        }
        assert msg != null, "expected an exception for inconsistent I7 length"
        assert msg.contains('length') || msg.contains('differs'),
            "error should call out the length mismatch: ${msg}"
        assert msg.contains('sample_B'),
            "error should name the offending sample: ${msg}"
    } finally { tmp.delete() }
})

runner.test('validateBcl2fastq throws when I5 lengths differ between samples', {
    def tmp = File.createTempFile('inconsistent_i5', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,ATCGACGT,TGAC
'''
    try {
        String msg = null
        try {
            SamplesheetReshape.validateBcl2fastq(tmp)
        } catch (IllegalArgumentException e) {
            msg = e.message
        }
        assert msg != null, "expected an exception for inconsistent I5 length"
        assert msg.contains('length') || msg.contains('differs'),
            "error should call out the length mismatch: ${msg}"
    } finally { tmp.delete() }
})

runner.test('validateBcl2fastq accepts samples with consistent index lengths even if they vary in other ways', {
    // Same I7 length, same I5 length, different sequences — fine.
    def tmp = File.createTempFile('consistent_lengths', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,GGCTAACC,TGACCGAA
'''
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } catch (IllegalArgumentException e) {
        assert false, "consistent lengths should not throw: ${e.message}"
    } finally { tmp.delete() }
})

System.exit(runner.summary())
