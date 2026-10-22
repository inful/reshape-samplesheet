#!/usr/bin/env nextflow
nextflow.enable.dsl=2

//
// reshape-samplesheet — demo entry point
//
// Reshape an Illumina samplesheet into the generic nf-core layout
// (sample, fastq_1, fastq_2) so it can be fed into an nf-core module.
//
// Two paths, chosen by --validate-only:
//
//   reshape path  (default)     needs fastq_dir — the fastqs must already
//                               exist because reshaping matches Sample_IDs
//                               against them.
//
//   --validate-only             needs only the samplesheet. Use this
//                               before running bcl2fastq/bclconvert, when
//                               no fastqs exist yet. Checks that the
//                               samplesheet is demultiplexable.
//

include { RESHAPE_SAMPLESHEET } from './subworkflows/nf-core/reshape_samplesheet/main.nf'

// Helper: require a non-empty param or fail with a clear error. Defined
// at script scope (not inside the workflow body) so it works around
// the Nextflow DSL parser's quirks with closure syntax.
def requireParam(name, value) {
    if (value == null || value == '') {
        error "Missing required parameter: --${name}"
    }
}

// Helper: coerce a CLI param to Boolean. Defined at script scope for
// the same reason as requireParam.
//
// Undefined (null) means false — nextflow.config deliberately provides
// no param defaults, so an omitted flag must read as "not set" rather
// than error. Bare `--flag` arrives as Boolean true; `--flag true`
// arrives as the String "true". Both are accepted. Anything else is a
// user error worth failing on loudly rather than silently reading as
// false.
def boolParam(name, value) {
    if (value == null || value == '') {
        return false
    }
    if (value instanceof Boolean) {
        return value
    }
    def s = value.toString().toLowerCase()
    if (s !in ['true', 'false']) {
        error "--${name} must be 'true' or 'false', got: '${value}'"
    }
    return (s == 'true')
}

workflow {

    // The samplesheet is the only input both paths require.
    requireParam('samplesheet', params.samplesheet)

    // Nextflow normalises CLI param names: `--validate-only` arrives as
    // params.validateOnly (camelCase) while `--validate_only` arrives as
    // params.validate_only. Accept either spelling so the documented
    // dashed form works regardless of which the caller typed.
    def validateOnly = boolParam(
        'validate_only', params.validateOnly ?: params.validate_only
    )

    // --validate-only: the pre-bcl2fastq gate. Deliberately requires no
    // fastq_dir / outdir, because at this point in the workflow no fastqs
    // exist yet — that is the whole point. Validates demultiplexability
    // (Sample_ID uniqueness, I7/I5 present and well-formed, consistent
    // lengths, unique combinations) and stops.
    //
    // Runs in-process via the lib rather than through the
    // validate_samplesheet sub-workflow: this is a gate, not a unit of
    // work, and main.nf already pre-flights in-process on the reshape
    // path below (see the SamplesheetReshape.validate call). Use the
    // sub-workflow when you want the check as a step inside a larger
    // pipeline rather than as a CLI gate.
    if (validateOnly) {

        def hammingAsError = boolParam(
            'hamming_distance_as_error',
            params.hammingDistanceAsError ?: params.hamming_distance_as_error
        )
        def validateOpts = new LinkedHashMap()
        validateOpts.hammingDistanceAsError = hammingAsError

        log.info """
            Validating samplesheet for demultiplexability
              samplesheet      : ${params.samplesheet}
              hamming as error : ${hammingAsError}
        """.stripIndent()

        // Throws IllegalArgumentException listing every problem at once
        // if anything is wrong — so the user fixes the whole samplesheet
        // in one pass rather than one error per attempt.
        SamplesheetReshape.validateBcl2fastq(
            file(params.samplesheet),
            validateOpts
        )

        log.info "Samplesheet is demultiplexable — safe to run bcl2fastq/bclconvert."

    } else {

        // ---- reshape path (unchanged) ----

        requireParam('fastq_dir',   params.fastq_dir)
        requireParam('outdir',      params.outdir)
        requireParam('recursive',   params.recursive)

        // Nextflow passes CLI params as Strings, so we coerce and validate.
        def recursive = boolParam('recursive', params.recursive)

        // strandedness is optional. The lib handles the Nextflow CLI
        // footguns (Boolean true from `--strandedness`, the string "true"
        // from `--strandedness ''`, the string "false", and empty string)
        // by coercing them all to null. So we can pass params.strandedness
        // through unchanged.
        def strandedness = params.strandedness

        log.info """
            Reshaping Illumina samplesheet into nf-core format
              samplesheet : ${params.samplesheet}
              fastq_dir   : ${params.fastq_dir}
              outdir      : ${params.outdir}
              recursive   : ${recursive}
              strandedness: ${strandedness ?: '<omitted>'}
        """.stripIndent()

        // Build the opts Map in a named variable rather than inline because
        // Nextflow's DSL parser can choke on Map literals in certain call
        // positions.
        def opts = new LinkedHashMap()
        opts.recursive = recursive
        if (strandedness != null) {
            opts.strandedness = strandedness
        }

        // No in-process pre-flight here on purpose. An earlier version called
        // SamplesheetReshape.validate() before the sub-workflow, which
        // ran the full sample-to-fastq matching twice per invocation (the
        // sub-workflow's SAMPLESHEET_VALIDATE module does the same work).
        //
        // The reason it looked load-bearing was data loss, and that was
        // a bug in the module rather than a reason to duplicate the
        // check: the module used to `rm` the previous CSV before
        // validating, so a failed run destroyed the last good result.
        // SAMPLESHEET_RESHAPE now stages into a workdir-local directory
        // and only places the file once the reshape has succeeded, so a
        // failed run leaves the previous CSV intact on its own.
        //
        // If you re-add an in-process pre-flight, keep it for the error
        // message only — it is not needed for correctness, and the
        // module's error already lists every problem at once.

        RESHAPE_SAMPLESHEET(
            file(params.samplesheet),
            file(params.fastq_dir),
            file(params.outdir),
            opts
        )

        RESHAPE_SAMPLESHEET.out.csv.view { csv -> println "Reshaped CSV: ${csv}" }

    }
}