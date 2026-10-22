//
// VALIDATE_SAMPLESHEET
//
// Check that an Illumina samplesheet is demultiplexable BEFORE running
// bcl2fastq or bclconvert, i.e. before any fastq files exist.
//
// This is the pre-demultiplexing counterpart to RESHAPE_SAMPLESHEET,
// which needs the fastq output to already be on disk. Use this one
// when you are about to spend hours of compute turning a run
// directory into fastqs and want to know the samplesheet won't
// produce garbage or a hard crash mid-run.
//
// Checks (all collected, then thrown together so the user can fix the
// whole samplesheet in one pass rather than one error per attempt):
//   - the samplesheet parses as bcl2fastq V1, BCLConvert V2, or LRM
//     (format auto-detected from the section markers)
//   - Sample_ID values are unique
//   - I7/I5 index cells are non-empty when the column exists
//   - I7/I5 sequences contain only A, C, G, T, N (case-insensitive)
//   - I7/I5 lengths are consistent across all samples
//   - I7+I5 index combinations are unique across all samples
//
// Hamming distance between indices in the same column is a warning by
// default (stderr, run continues); set `hammingDistanceAsError: true`
// in the opts map to make it fatal.
//
// Inputs:
//   ch_samplesheet  channel: [ path(samplesheet.csv) ]
//   ch_opts         channel: [ {hammingDistanceAsError: false} ]
//
// Emits:
//   versions  channel: [ path(versions.yml) ]
//
// There is deliberately no data emit. Success is the absence of an
// exception; the process either completes (the samplesheet is
// demultiplexable) or fails the task with the complete problem list.
//

include { SAMPLESHEET_VALIDATE_BCL2FASTQ } from '../../../modules/nf-core/samplesheet_validate_bcl2fastq/main'

workflow VALIDATE_SAMPLESHEET {

    take:
    ch_samplesheet    // channel: [ path(samplesheet.csv) ]
    ch_opts           // channel: [ map ]

    main:
    ch_versions = Channel.empty()

    SAMPLESHEET_VALIDATE_BCL2FASTQ ( ch_samplesheet, ch_opts )
    ch_versions = ch_versions.mix( SAMPLESHEET_VALIDATE_BCL2FASTQ.out.versions )

    emit:
    versions  = ch_versions  // channel: [ path(versions.yml) ]
}