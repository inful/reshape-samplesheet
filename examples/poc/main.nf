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

workflow {

    // Fail fast on missing required parameters with a clear message.
    requireParam('samplesheet', params.samplesheet)
    requireParam('fastq_dir',   params.fastq_dir)
    requireParam('outdir',      params.outdir)
    requireParam('recursive',   params.recursive)

    // Nextflow passes CLI params as Strings, so coerce and validate.
    def recursiveStr = params.recursive.toString().toLowerCase()
    if (recursiveStr !in ['true', 'false']) {
        error "--recursive must be 'true' or 'false', got: '${params.recursive}'"
    }
    def recursive = (recursiveStr == 'true')

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

    // Pre-flight check (synchronous — fails before any process submits).
    // Same pattern as the root main.nf: catch bad input early so the
    // user sees a clear error before compute is billed.
    SamplesheetReshape.validate(
        file(params.samplesheet),
        file(params.fastq_dir),
        opts
    )

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
