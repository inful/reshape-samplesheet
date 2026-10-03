//
// RESHAPE_SAMPLESHEET
//
// Read an Illumina samplesheet (bcl2fastq or Local Run Manager format),
// match Sample_IDs against fastq files in a directory, and emit a CSV
// in the generic nf-core layout:
//
//     sample,fastq_1,fastq_2[,strandedness]
//
// Multiple fastq files per sample (e.g. across lanes) are joined with
// a comma into a single cell, per nf-core convention. Single-end samples
// get an empty fastq_2 cell.
//
// Composes two nf-core modules:
//   - SAMPLESHEET_VALIDATE  — pre-flight check that fails fast on bad input
//   - SAMPLESHEET_RESHAPE   — the actual reshape + CSV write
//
// Inputs:
//   ch_samplesheet  channel: [ path(samplesheet.csv) ]
//   ch_fastq_dir    channel: [ path(fastq_dir) ]
//   ch_output_dir   channel: [ path(output_dir) ]
//   ch_opts         channel: [ {recursive: false, strandedness: null, pattern: null, validateStructure: false} ]
//
// Emits:
//   csv       channel: [ path(*.nfcore.csv) ]
//   versions  channel: [ path(versions.yml) ]
//

include { SAMPLESHEET_VALIDATE } from '../../../modules/nf-core/samplesheet_validate/main'
include { SAMPLESHEET_RESHAPE  } from '../../../modules/nf-core/samplesheet_reshape/main'

workflow RESHAPE_SAMPLESHEET {

    take:
    ch_samplesheet    // channel: [ path(samplesheet.csv) ]
    ch_fastq_dir      // channel: [ path(fastq_dir) ]
    ch_output_dir     // channel: [ path(output_dir) ]
    ch_opts           // channel: [ map ]

    main:
    ch_versions = Channel.empty()

    // Pre-flight: runs every check that reshape() would, but discards
    // the output. Throws IllegalArgumentException with the complete list
    // of problems if anything is wrong, so the pipeline can fail before
    // any compute is billed.
    SAMPLESHEET_VALIDATE ( ch_samplesheet, ch_fastq_dir, ch_opts )
    ch_versions = ch_versions.mix( SAMPLESHEET_VALIDATE.out.versions )

    // Reshape: produces the nf-core CSV in ch_output_dir.
    SAMPLESHEET_RESHAPE ( ch_samplesheet, ch_fastq_dir, ch_output_dir, ch_opts )
    ch_versions = ch_versions.mix( SAMPLESHEET_RESHAPE.out.versions )

    emit:
    csv       = SAMPLESHEET_RESHAPE.out.csv        // channel: [ path(csv) ]
    versions  = ch_versions                        // channel: [ path(versions.yml) ]
}