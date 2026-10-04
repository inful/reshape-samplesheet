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
// a comma into a single cell, RFC-4180 quoted. Single-end samples
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
//   samples   channel: [ tuple([id, single_end], [path, ...], [path, ...]) ]
//                       Structured records — meta + per-direction file
//                       lists, ready to be re-tupled into a
//                       (meta, reads) pair by the consumer. This is
//                       the "three lines to feed a downstream module"
//                       path: see examples/poc/main.nf for the
//                       shape and a fastqc example.
//   versions  channel: [ path(versions.yml) ]
//
// The csv and samples emits are two views of the same data. csv is
// the canonical artifact (good for inspection, debugging, and
// external tools that consume samplesheets as files); samples is
// the programmatic hand-off for Nextflow-based downstream
// pipelines. Both are always emitted, and they are guaranteed to
// agree because the samples emit is built by parsing the csv emit
// through the lib's canonical ReshapedCsvParser.
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

    // Build the structured `samples` emit by parsing the CSV
    // through the lib's canonical ReshapedCsvParser. Lives in
    // the sub-workflow (not the consumer) so every consumer of
    // RESHAPE_SAMPLESHEET gets ready-to-use tuples without having
    // to re-derive them. See ReshapedCsvParser for the
    // RFC-4180 parsing and the per-record shape.
    //
    // The lib returns java.io.File objects (it has no Nextflow
    // dependency by design). We convert them to Nextflow's
    // Path objects here so downstream modules' `path(reads,
    // stageAs: '...')` input channels accept them — the
    // Nextflow `file()` function returns the wrapped Path.
    SAMPLESHEET_RESHAPE.out.csv
        .flatMap { csv -> SamplesheetReshape.parseReshapedCsv(csv) }
        .map { record ->
            def fastq1 = record.fastq_1.collect { file(it.toString()) }
            def fastq2 = record.fastq_2.collect { file(it.toString()) }
            [record.meta, fastq1, fastq2]
        }
        .set { ch_samples }

    emit:
    csv       = SAMPLESHEET_RESHAPE.out.csv        // channel: [ path(csv) ]
    samples   = ch_samples                         // channel: [ [meta, [Path], [Path]] ]
    versions  = ch_versions                        // channel: [ path(versions.yml) ]
}