# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-10-03

### Added
- Initial release of `reshape-samplesheet`.
- Pure-Groovy parser for Illumina samplesheets in both bcl2fastq (`[Data]`
  section) and Local Run Manager (headerless) formats. Handles quoted fields
  with embedded commas, escaped quotes, and embedded newlines; reads as UTF-8;
  strips BOM; tolerates CRLF / LF / CR line endings; accepts non-ASCII
  sample names.
- File-matching engine with a strict default (prefix + separator) and a
  user-overridable regex template via `opts.pattern`. Catches the documented
  footgun where a short `Sample_ID` like `A` would otherwise false-match
  `ABC_S1_L001_R1_001.fastq.gz`.
- Optional 4th `strandedness` column. Coerces Nextflow's `--strandedness ''`
  footgun (which collapses to the String `"true"`) to `null` so the
  default 3-column output is preserved.
- Pre-flight validation entry points: `validate(samplesheet)`,
  `validate(samplesheet, fastqDir)`, and `validate(samplesheet, fastqDir, opts)`
  for the full pre-flight; `validateBcl2fastq(samplesheet)` for the
  bcl2fastq-only structural checks (run before sequencing, when no FASTQ
  files exist yet).
- Opt-in bcl2fastq structural validation through `opts.validateStructure: true`
  for an end-of-pipeline pre-flight that runs both the bcl2fastq checks
  and the file matching in one pass. Checks Sample_ID uniqueness,
  I7/I5 index sequence format (A/C/G/T/N, normalised to uppercase),
  consistent I7/I5 lengths across samples, and unique I7+I5 index
  combinations.
- Nextflow sub-workflow `RESHAPE_SAMPLESHEET` exposed at
  `subworkflows/local/reshape_samplesheet.nf` for `include` from other
  pipelines.
- Strict error contract: every malformed input throws
  `IllegalArgumentException` with the file path and, where applicable,
  the complete list of detected problems in one error message. No silent
  partial output.
- 79 unit tests across 5 test files (parser, reshaper, validator,
  options, matching) covering happy paths, edge cases, malformed inputs,
  every coercion footgun, the matching footguns, and the bcl2fastq
  structural rules.
- Smoke test in `bin/test.sh --smoke` that runs `main.nf` end-to-end
  for users with only Nextflow installed (no separate Groovy required).
- README with usage, options, matching-strategy documentation, error
  matrix, and extension points.