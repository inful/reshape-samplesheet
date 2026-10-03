#!/usr/bin/env nextflow
nextflow.enable.dsl=2

//
// reshape-samplesheet — demo entry point
//
// Reshape an Illumina samplesheet into the generic nf-core layout
// (sample, fastq_1, fastq_2) so it can be fed into an nf-core module.
//

include { RESHAPE_SAMPLESHEET } from './subworkflows/local/reshape_samplesheet.nf'

// Helper: require a non-empty param or fail with a clear error. Defined
// at script scope (not inside the workflow body) so it works around
// the Nextflow DSL parser's quirks with closure syntax.
def requireParam(name, value) {
    if (value == null || value == '') {
        error "Missing required parameter: --${name}"
    }
}

workflow {

    // Fail fast on missing required parameters with a clear message.
    requireParam('samplesheet', params.samplesheet)
    requireParam('fastq_dir',   params.fastq_dir)
    requireParam('outdir',      params.outdir)
    requireParam('recursive',   params.recursive)

    // Nextflow passes CLI params as Strings, so we coerce and validate.
    def recursiveStr = params.recursive.toString().toLowerCase()
    if (recursiveStr !in ['true', 'false']) {
        error "--recursive must be 'true' or 'false', got: '${params.recursive}'"
    }
    def recursive = (recursiveStr == 'true')

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

    // Pre-flight check: run every validation the sub-workflow would,
    // before any compute is submitted. If anything is wrong, this
    // throws IllegalArgumentException with a clear, complete error
    // message (e.g. "3 samples have no matching fastq files: A, B, C").
    // This is the single most important line in the whole pipeline
    // for protecting the user from silent partial-output runs.
    SamplesheetReshape.validate(
        file(params.samplesheet),
        file(params.fastq_dir),
        opts
    )

    RESHAPE_SAMPLESHEET(
        file(params.samplesheet),
        file(params.fastq_dir),
        file(params.outdir),
        opts
    )

    RESHAPE_SAMPLESHEET.out.csv.view { csv -> println "Reshaped CSV: ${csv}" }
}
