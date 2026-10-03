//
// RESHAPE_SAMPLESHEET
//
// Read an Illumina samplesheet (bcl2fastq or Local Run Manager format),
// match Sample_IDs against fastq files in a directory, and emit a CSV
// in the generic nf-core layout:
//
//     sample,fastq_1,fastq_2
//
// Multiple fastq files per sample (e.g. across lanes) are joined with
// a comma into a single cell, per nf-core convention. Single-end samples
// get an empty fastq_2 cell.
//
// Inputs:
//   illumina_samplesheet  path  — Illumina samplesheet CSV
//   fastq_dir             path  — directory containing fastq(.gz) files
//   output_dir            path  — directory to write the reshaped CSV
//   opts                  Map   — options. Recognised keys:
//                                   - recursive     (Boolean) — walk
//                                     fastq_dir recursively
//                                   - strandedness  (String)  — when
//                                     non-null, add a 4th column
//                                     with this value for every sample
//                                 Unknown keys are silently ignored.
//
// Emits:
//   csv                   path  — the reshaped CSV file
//

workflow RESHAPE_SAMPLESHEET {

    take:
        illumina_samplesheet
        fastq_dir
        output_dir
        opts

    main:
        // The lib's Object + Map overloads handle Nextflow Path / String /
        // File conversion internally, so we can pass the take values
        // through unchanged.
        def outFile = SamplesheetReshape.writeReshaped(
            output_dir,
            illumina_samplesheet,
            fastq_dir,
            opts
        )

    emit:
        csv = outFile
}
