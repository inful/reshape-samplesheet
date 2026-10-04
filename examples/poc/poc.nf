//
// POC_RESHAPE_AND_FASTQC — named sub-workflow for the POC.
//
// The composition: RESHAPE_SAMPLESHEET → CSV → structured tuples
// (via the new `out.samples` emit, parsed by the lib's
// ReshapedCsvParser) → FASTQC. The whole point of the POC is
// that a consumer gets to a real nf-core analysis module in
// three lines:
//
//     POC_RESHAPE_AND_FASTQC(
//         file(samplesheet),
//         file(fastq_dir),
//         file(outdir),
//         [recursive: false]
//     )
//
// The reshape lib does the Sample_ID → fastq matching once; the
// sub-workflow hands the consumer ready-to-use `(meta, reads)`
// tuples via `out.fastqc_input`. The consumer never has to parse
// the CSV or re-derive the file matching.
//
// Inputs:
//   ch_samplesheet  channel: [ path(samplesheet.csv) ]
//   ch_fastq_dir    channel: [ path(fastq_dir) ]
//   ch_output_dir   channel: [ path(output_dir) ]
//   ch_opts         channel: [ map ]
//
// Emits:
//   csv             channel: [ path(*.nfcore.csv) ]
//   fastqc_input    channel: [ tuple([id, single_end], [path, ...]) ]
//   fastqc_html     channel: [ tuple([id, single_end], path(*.html)) ]
//   fastqc_zip      channel: [ tuple([id, single_end], path(*.zip)) ]
//   versions        channel: [ path(versions.yml) ]
//

include { RESHAPE_SAMPLESHEET } from '../../subworkflows/nf-core/reshape_samplesheet/main.nf'
include { FASTQC             } from './nf-core-modules/modules/nf-core/fastqc/main.nf'

workflow POC_RESHAPE_AND_FASTQC {

    take:
    ch_samplesheet    // channel: [ path(samplesheet.csv) ]
    ch_fastq_dir      // channel: [ path(fastq_dir) ]
    ch_output_dir     // channel: [ path(output_dir) ]
    ch_opts           // channel: [ map ]

    main:
    ch_versions = Channel.empty()

    // 1) Reshape the Illumina samplesheet into the generic nf-core
    //    CSV. RESHAPE_SAMPLESHEET also exposes a pre-parsed
    //    `samples` channel of (meta, fastq_1, fastq_2) tuples —
    //    this is the "three lines" win that the POC exists to
    //    demonstrate.
    RESHAPE_SAMPLESHEET(ch_samplesheet, ch_fastq_dir, ch_output_dir, ch_opts)
    ch_versions = ch_versions.mix(RESHAPE_SAMPLESHEET.out.versions)

    // 2) Three lines: reshape output → fastqc input. Combine the
    //    per-direction file lists into a single reads list
    //    (FASTQC's input contract is one tuple per sample with all
    //    reads in a single list).
    RESHAPE_SAMPLESHEET.out.samples
        .map { meta, fq1, fq2 -> [meta, fq1 + fq2] }
        .set { ch_fastqc_input }

    // 3) Run FASTQC on every sample.
    FASTQC(ch_fastqc_input)
    ch_versions = ch_versions.mix(FASTQC.out.versions_fastqc)

    emit:
    csv          = RESHAPE_SAMPLESHEET.out.csv
    fastqc_input = ch_fastqc_input
    fastqc_html  = FASTQC.out.html
    fastqc_zip   = FASTQC.out.zip
    versions     = ch_versions
}
