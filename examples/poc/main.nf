#!/usr/bin/env nextflow
nextflow.enable.dsl=2

//
// POC — reshape-samplesheet → nf-core/fastqc (CLI entry point)
//
// A thin wrapper that translates params to channel inputs and
// delegates to the named sub-workflow in `poc.nf`. Run with:
//
//   nextflow run examples/poc/main.nf \
//       --samplesheet tests/data/illumina_bcl2fastq.csv \
//       --fastq_dir   tests/fastqs \
//       --outdir      results-poc \
//       --recursive    false
//
// For programmatic inclusion (e.g. from another pipeline), use poc.nf:
//
//   include { POC_RESHAPE_AND_FASTQC } from './examples/poc/poc.nf'
//
// One-time setup (see `bin/setup.sh`):
//   1. Clones nf-core/modules at a pinned SHA into
//      `examples/poc/nf-core-modules/` (gitignored).
//   2. Copies the repo-root `lib/` into `examples/poc/lib/` so the
//      reshape sub-workflow can find SamplesheetReshape and
//      ReshapedCsvParser on the classpath when run from a
//      sub-directory.
//

include { POC_RESHAPE_AND_FASTQC } from './poc.nf'

// Helper: require a non-empty param or fail with a clear error. Mirrors
// the helper in ../../main.nf — duplicated here rather than imported
// because Nextflow has no clean way to share helper functions across
// pipeline entry points.
def requireParam(name, value) {
    if (value == null || value == '') {
        error "Missing required parameter: --${name}"
    }
}

// Helper: coerce a CLI param to Boolean. Mirrors boolParam in
// ../../main.nf, duplicated for the same reason as requireParam above.
// Kept in sync deliberately: this copy existed in the older inline form
// while the root entry point had already moved to a shared helper.
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

    // Fail fast on missing required parameters with a clear message.
    requireParam('samplesheet', params.samplesheet)
    requireParam('fastq_dir',   params.fastq_dir)
    requireParam('outdir',      params.outdir)
    requireParam('recursive',   params.recursive)

    // Nextflow passes CLI params as Strings, so coerce and validate.
    def recursive = boolParam('recursive', params.recursive)

    // FASTQC doesn't read strandedness, so the 3-column CSV is
    // what we want here.
    def opts = new LinkedHashMap()
    opts.recursive = recursive

    log.info """
        POC: reshape Illumina samplesheet → nf-core/fastqc
          samplesheet : ${params.samplesheet}
          fastq_dir   : ${params.fastq_dir}
          outdir      : ${params.outdir}
          recursive   : ${recursive}
    """.stripIndent()

    // No in-process pre-flight here, matching the root main.nf: the
    // SAMPLESHEET_VALIDATE module inside RESHAPE_SAMPLESHEET does the
    // same work, and SAMPLESHEET_RESHAPE stages its output so a failed
    // run cannot destroy the previous CSV. Running the matching twice
    // per invocation bought nothing.

    // Convert each scalar param into a single-element channel so
    // POC_RESHAPE_AND_FASTQC (a take/main/emit sub-workflow) can be
    // invoked with the standard channel pattern.
    POC_RESHAPE_AND_FASTQC(
        Channel.fromPath(params.samplesheet, checkIfExists: true),
        Channel.fromPath(params.fastq_dir,   checkIfExists: true),
        Channel.fromPath(params.outdir,      checkIfExists: false),
        Channel.value(opts)
    )

    // Surface the per-sample fastqc outputs in the run log so a
    // human running the POC can see the end-to-end result without
    // grepping the workdir.
    POC_RESHAPE_AND_FASTQC.out.fastqc_html.view { meta, html ->
        log.info "FastQC report: ${meta.id} (single_end=${meta.single_end}) → ${html}"
    }
}
