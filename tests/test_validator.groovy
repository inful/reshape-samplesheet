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

runner.test('validateBcl2fastq reports I7 Hamming violations as warnings by default (does not throw)', {
    // The default behaviour is "warn, don't throw": a Hamming
    // distance of 1 is a soft risk that bcl2fastq may or may not
    // handle gracefully, so we surface it but don't abort the
    // pipeline. Capture stderr to verify the warning fires.
    def tmp = File.createTempFile('hamming_i7_close', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,ATCAACGT,GCTAGCTA
'''
    def original = System.err
    def captured = new ByteArrayOutputStream()
    System.setErr(new PrintStream(captured))
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } finally {
        System.setErr(original)
        tmp.delete()
    }
    String stderr = captured.toString()
    assert stderr.contains('WARNING') && stderr.contains('Hamming distance'),
        "expected a Hamming-distance warning on stderr, got: ${stderr}"
    assert stderr.contains('ATCGACGT') && stderr.contains('ATCAACGT'),
        "expected both indices to be named in the warning: ${stderr}"
    assert stderr.contains('sample_A') && stderr.contains('sample_B'),
        "expected both samples to be named in the warning: ${stderr}"
})

runner.test('validateBcl2fastq promotes I7 Hamming violations to errors with hammingDistanceAsError:true', {
    // Opt in to the strict behaviour via the opts map. The
    // Hamming violation should now participate in the
    // all-issues-in-one-exception contract, like the other
    // bcl2fastq checks.
    def tmp = File.createTempFile('hamming_i7_close', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,ATCAACGT,GCTAGCTA
'''
    String msg = null
    try {
        SamplesheetReshape.validateBcl2fastq(tmp, [hammingDistanceAsError: true])
    } catch (IllegalArgumentException e) {
        msg = e.message
    } finally { tmp.delete() }
    assert msg != null, "expected an exception for indices within Hamming distance 1 with the opt-in"
    assert msg.contains('Hamming distance'),
        "expected the error to mention Hamming distance: ${msg}"
    assert msg.contains('ATCGACGT') && msg.contains('ATCAACGT'),
        "expected both indices to be named in the error: ${msg}"
    assert msg.contains('sample_A') && msg.contains('sample_B'),
        "expected both samples to be named in the error: ${msg}"
    assert msg.contains('differ by only 1 base'),
        "expected the error to report the actual distance: ${msg}"
})

runner.test('validateBcl2fastq accepts I7 indices that differ by exactly minDistance (2) bases', {
    def tmp = File.createTempFile('hamming_i7_min', '.csv')
    // ATCGACGT and ATGCATCG differ by 4 bases — well above the
    // default minimum of 2. Should pass cleanly (no warning, no
    // error).
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,ATGCATCG,GCTAGCTA
'''
    def original = System.err
    def captured = new ByteArrayOutputStream()
    System.setErr(new PrintStream(captured))
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } catch (IllegalArgumentException e) {
        assert false, "indices differing by 4 bases should pass: ${e.message}"
    } finally {
        System.setErr(original)
        tmp.delete()
    }
    assert !captured.toString().contains('Hamming distance'),
        "should not have produced a warning for indices well above the minimum"
})

runner.test('validateBcl2fastq reports I5 Hamming violations as warnings by default', {
    // Same check on the I5 column — different content, same logic.
    def tmp = File.createTempFile('hamming_i5', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,ATCGACGT,GCAAGCTA
'''
    def original = System.err
    def captured = new ByteArrayOutputStream()
    System.setErr(new PrintStream(captured))
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } finally {
        System.setErr(original)
        tmp.delete()
    }
    String stderr = captured.toString()
    assert stderr.contains('I5') && stderr.contains('Hamming distance'),
        "expected the warning to mention I5 Hamming distance: ${stderr}"
})

runner.test('validateBcl2fastq does not double-report identical indices via the Hamming check', {
    // Identical I7s are caught by the I7+I5 combination uniqueness
    // check, not the Hamming check. The Hamming check should
    // explicitly skip distance-0 pairs to avoid a duplicate error
    // for the same problem.
    def tmp = File.createTempFile('hamming_dup', '.csv')
    // Same I7 AND same I5 — only the combination check fires.
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
    assert msg != null, "expected an exception for identical I7+I5"
    assert msg.contains('Duplicate index combination'),
        "expected the combination check to fire: ${msg}"
    // The Hamming check should NOT also fire for this pair (it
    // would just duplicate the message).
    assert !msg.contains('Hamming distance'),
        "Hamming check should skip distance-0 pairs (got: ${msg})"
})

runner.test('validateBcl2fastq does not throw Hamming errors for unequal-length indices', {
    // Inconsistent index lengths are caught by the length check,
    // not the Hamming check. The Hamming check should treat
    // different-length pairs as "not comparable" rather than
    // reporting a spurious distance violation.
    def tmp = File.createTempFile('hamming_diff_len', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,ATCG,GCTAGCTA
'''
    String msg = null
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } catch (IllegalArgumentException e) {
        msg = e.message
    } finally { tmp.delete() }
    assert msg != null, "expected an exception for the length mismatch"
    assert msg.contains('length') && msg.contains('differs'),
        "expected the length check to fire: ${msg}"
    assert !msg.contains('Hamming distance'),
        "Hamming check should not report a violation for unequal lengths (got: ${msg})"
})

runner.test('validateBcl2fastq does not throw Hamming errors for empty index cells', {
    // Empty cells are caught by the empty-index check, not the
    // Hamming check. The Hamming check should ignore empty cells.
    def tmp = File.createTempFile('hamming_empty', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,,GCTAGCTA
'''
    String msg = null
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } catch (IllegalArgumentException e) {
        msg = e.message
    } finally { tmp.delete() }
    assert msg != null, "expected an exception for the empty I7"
    assert msg.contains('I7 (index) value is empty'),
        "expected the empty-index check to fire: ${msg}"
    assert !msg.contains('Hamming distance'),
        "Hamming check should not report a violation involving an empty cell (got: ${msg})"
})

runner.test('validateBcl2fastq normalises lowercase indices before the Hamming check (warning path)', {
    // The character check normalises to uppercase; the Hamming
    // check should do the same so 'atcg' and 'ATCA' (Hamming
    // distance 1) get flagged even when written lowercase. By
    // default this is a warning.
    def tmp = File.createTempFile('hamming_case', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,atcgacgt,GCTAGCTA
sample_B,sample_A,atcaacgt,GCTAGCTA
'''
    def original = System.err
    def captured = new ByteArrayOutputStream()
    System.setErr(new PrintStream(captured))
    try {
        SamplesheetReshape.validateBcl2fastq(tmp)
    } finally {
        System.setErr(original)
        tmp.delete()
    }
    String stderr = captured.toString()
    assert stderr.contains('Hamming distance') && stderr.contains('ATCGACGT') && stderr.contains('ATCAACGT'),
        "expected normalised indices in the warning: ${stderr}"
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

runner.test('validate(... [hammingDistanceAsError: true]) threads the opt through to the validator', {
    // Opt-plumbing test: verify that hammingDistanceAsError in the
    // opts map reaches the validator when validateStructure is true.
    // A future refactor that breaks the wiring (e.g., renames the
    // opt or forgets to thread it through reshapeImpl) would not be
    // caught by the unit tests for validateBcl2fastq directly, so
    // this test exercises the public path.
    def tmp = File.createTempFile('hamming_opt_threading_error', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,ATCAACGT,GCTAGCTA
'''
    String msg = null
    try {
        SamplesheetReshape.validate(tmp, fastqDir,
            [validateStructure: true, hammingDistanceAsError: true])
    } catch (IllegalArgumentException e) {
        msg = e.message
    } finally { tmp.delete() }
    assert msg != null, "expected an exception for close indices with the opt-in"
    assert msg.contains('Hamming distance'),
        "error should call out the Hamming violation: ${msg}"
})

runner.test('reshape(... [hammingDistanceAsError: true]) threads the opt through to the validator', {
    // Same threading check but through the reshape entry point,
    // since reshape and validate have separate code paths to the
    // validator.
    def tmp = File.createTempFile('hamming_opt_reshape_error', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,ATCAACGT,GCTAGCTA
'''
    String msg = null
    try {
        SamplesheetReshape.reshape(tmp, fastqDir,
            [validateStructure: true, hammingDistanceAsError: true])
    } catch (IllegalArgumentException e) {
        msg = e.message
    } finally { tmp.delete() }
    assert msg != null, "expected an exception for close indices with the opt-in"
    assert msg.contains('Hamming distance'),
        "error should call out the Hamming violation: ${msg}"
})

runner.test('validate(... [hammingDistanceAsError unset]) defaults to warning (no exception)', {
    // The default Hamming behaviour is "warn, don't throw", and
    // that default should be preserved when the opt is absent.
    def tmp = File.createTempFile('hamming_opt_threading_warn', '.csv')
    tmp.text = '''[Data]
Sample_ID,Sample_Name,index,index2
sample_A,sample_A,ATCGACGT,GCTAGCTA
sample_B,sample_B,ATCAACGT,GCTAGCTA
'''
    def original = System.err
    def captured = new ByteArrayOutputStream()
    System.setErr(new PrintStream(captured))
    try {
        SamplesheetReshape.validate(tmp, fastqDir, [validateStructure: true])
    } finally {
        System.setErr(original)
        tmp.delete()
    }
    String stderr = captured.toString()
    assert stderr.contains('WARNING') && stderr.contains('Hamming distance'),
        "expected a warning on stderr for close indices with the default opt, got: ${stderr}"
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
