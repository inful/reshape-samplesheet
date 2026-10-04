# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Hamming distance check in the bcl2fastq validator.** For
  every pair of indices in the same column (I7 and I5), the
  validator now flags any pair whose Hamming distance is below
  the default minimum of 2. Catches demultiplexing risks where a
  single sequencing error could cross-assign reads to the wrong
  sample (e.g. `ATCGACGT` and `ATCAACGT` differ by one base).
  Skips pairs already caught by other checks (identical indices
  → I7+I5 combination check; empty or unequal-length indices →
  their respective checks). Default of 2 matches
  `nf-core/samplesheetparser/validate`. 7 new unit tests in
  `tests/test_validator.groovy` cover I7, I5, identical-pair
  de-duplication, unequal-length handling, empty-cell handling,
  lowercase normalisation, and the just-above-the-limit pass
  case. Total unit tests: 98 (was 91).
- **New `out.samples` emit on `RESHAPE_SAMPLESHEET`** — a pre-parsed
  channel of `[meta: [id, single_end], fastq_1: [Path, ...],
  fastq_2: [Path, ...]]` tuples, built by parsing the emitted CSV
  through the lib's canonical `ReshapedCsvParser`. This is the
  "three lines to a real nf-core analysis module" hand-off: a
  consumer goes from the CSV emit to a downstream module with
  no inline parser and no RFC-4180 handling. The CSV emit stays
  for human inspection and external tools; the new `samples`
  emit is the programmatic hand-off. See the updated
  `subworkflows/nf-core/reshape_samplesheet/meta.yml` for the
  contract.
- **New `ReshapedCsvParser` class in the main lib**
  (`lib/ReshapedCsvParser.groovy`) and public API on
  `SamplesheetReshape.parseReshapedCsv(file)`. RFC-4180-aware
  parser that reads the lib's own output CSV and returns
  structured records. 11 unit tests in
  `tests/test_reshaped_csv_parser.groovy` cover happy path,
  quoted multi-value cells, edge cases (UTF-8 BOM, CRLF,
  escaped quotes), and malformed input. End-to-end test asserts
  the parser round-trips a CSV produced by the lib itself.
- **POC: reshape-samplesheet → nf-core/fastqc** at `examples/poc/`.
  A self-contained proof of concept that takes the next step
  beyond emitting the CSV: it reads `RESHAPE_SAMPLESHEET.out.samples`
  and hands the tuples to `nf-core/fastqc` to produce per-sample
  QC reports — including the multi-lane `sample_A`, which
  produces 4 reports (2 R1 lanes + 2 R2 lanes). The consumer
  code in the POC's named sub-workflow is the canonical
  three-line pattern; see `examples/poc/README.md` for the
  full architecture, caveats, and test layers. The POC has its
  own `bin/setup.sh` (clones `nf-core/modules@<pinned SHA>`,
  stages `lib/` from the repo root) and runs end-to-end via
  Docker (the FastQC container is pulled on first run; pass
  `docker.enabled = false` to use a local `fastqc` instead).
- New `bin/test.sh --poc-smoke` and `bin/test.sh --poc-test`
  modes. `--poc-smoke` runs the real FastQC against the test
  fixtures and asserts all 4 samples (including the multi-lane
  `sample_A`) have non-empty HTML reports. `--poc-test` adds
  the structural nf-test in stub mode. The `auto` mode also
  runs `--poc-smoke` if `examples/poc/bin/setup.sh` has been
  executed.
- New nf-test case `bcl2fastq - samples emit - structural
  assertions on (meta, fastq_1, fastq_2) tuples` in
  `subworkflows/nf-core/reshape_samplesheet/tests/main.nf.test`
  that asserts the new emit's shape (one tuple per sample,
  single-end detection, multi-lane file lists).
- New CI step `Run POC smoke test` in
  `.github/workflows/ci.yml` that runs the real FastQC against
  the test fixtures. Skipped if
  `examples/poc/nf-core-modules/modules/nf-core/fastqc/main.nf`
  is absent (i.e. the one-time setup hasn't been run on the
  CI runner).

### Fixed
- **`SamplesheetReshaper` now RFC-4180-quotes multi-value cells.**
  Previously, the reshape lib wrote multi-value cells (multiple
  fastq paths joined by `,`) without surrounding double quotes,
  which made the resulting CSV unparseable by `splitCsv(header: true)`
  and any other standards-compliant CSV reader. The README's
  "per nf-core convention" claim was misleading — the actual
  convention is quoted multi-value cells (matching rnaseq, sarek,
  ampliseq, etc.). The lib now wraps any cell containing `,` in
  double quotes; single-value cells are left unquoted. Existing
  unit tests still pass; a new unit test asserts the quoting
  behaviour on both multi-lane and single-lane samples.

## [0.2.1] - 2026-10-03

### Added
- **Restored the 79-test bespoke Groovy unit suite** (`tests/test_*.groovy`)
  as a fast, in-process CI layer. Runs in ~10 seconds, no Nextflow or
  nf-test required. Covers every parser / validate / reshape /
  matching / opts-coercion edge case at the lib level — the cases
  that nf-test (which only checks the sub-workflow emit path) can't
  reach. Restored because the nf-test suite alone is shallow: only
  workflow.success + a few CSV header checks, not semantic coverage.
- New CI job `unit` in `.github/workflows/ci.yml` that runs the unit
  suite independently of the Nextflow matrix, so it catches lib
  regressions in seconds without installing Nextflow.
- New `bin/test.sh --unit` mode. Default mode (`auto`) now runs
  `unit + nf-test` if nf-test is on PATH, `unit + smoke` if only
  Nextflow is on PATH, or `unit` alone otherwise.

### Changed
- `bin/test.sh` rewritten with four explicit modes (`--unit`,
  `--nf-test`, `--smoke`, default `auto`) and a tally of total
  pass/fail across all unit-test files.

## [0.2.0] - 2026-10-03

### Changed
- **Restructured to the nf-core publish layout.** The sub-workflow moved
  from `subworkflows/local/reshape_samplesheet.nf` to
  `subworkflows/nf-core/reshape_samplesheet/main.nf` (the layout
  `nf-core subworkflows install` consumes). Two thin module wrappers
  in `modules/nf-core/{samplesheet_validate,samplesheet_reshape}/`
  satisfy nf-core's `≥2 modules` rule and call into the same `lib/`
  Groovy implementation as before. `meta.yml` for the sub-workflow
  and both modules documents inputs, channel structures, authors,
  maintainers, and the `versions` channel emit.
- **Test runner switched from bespoke Groovy to nf-test.** The 79-test
  Groovy suite is retired; the new nf-test suite in
  `subworkflows/nf-core/reshape_samplesheet/tests/main.nf.test` covers
  the happy path, the `strandedness` opt, the LRM format, an
  intentional failure (orphan sample), and the required stub test.
  `bin/test.sh` now runs `nf-test` instead of Groovy.
- **Module scripts ported for cross-platform portability.** Replaced
  GNU-only `find -exec cp -t ...` with a portable bash `for` loop.
  Works identically on Linux CI runners and macOS dev machines.
- **`bin/test.sh` modes renamed.** `--full` is gone (replaced by
  `--nf-test`); `--smoke` unchanged; the default now prefers nf-test
  and falls back to smoke if nf-test isn't installed.
- **`main.nf` updated** to include the sub-workflow from its new
  `subworkflows/nf-core/reshape_samplesheet/main.nf` path.
- **nf-test assertions replaced with structural checks.** The CSV
  contains absolute fastq paths that include the workdir, so a strict
  `snapshot(workflow.out)` would produce a different MD5 every CI run.
  Replaced with `assert workflow.success` + per-test structural
  assertions (emit channel non-empty, expected basename, header row,
  row count, strandedness tail column).

### Added
- New CI job **`lint-nf-core`** in `.github/workflows/ci.yml` that
  runs a structural nf-core layout check on every push and PR
  (every module has `main.nf` + `meta.yml`, every sub-workflow has
  `main.nf` + `meta.yml` + `tests/main.nf.test`, sub-workflow
  `meta.yml` lists ≥2 components). The real `nf-core subworkflows
  lint` is documented as a follow-up that runs against a fork of
  nf-core/modules at submission time.
- New CI matrix step runs the **nf-test** suite (`bin/test.sh
  --nf-test`) on Nextflow 25.10.4 and 26.04.4.
- New CI matrix step also runs the **smoke test** (`bin/test.sh
  --smoke`) on the same two Nextflow versions.
- New test fixture **`tests/data/illumina_lrm_with_orphan.csv`** —
  a Local Run Manager samplesheet with an extra `sample_orphan` row
  that has no matching fastqs, used by the "missing fastq match"
  nf-test case.

### Removed
- **`subworkflows/local/reshape_samplesheet.nf`** — replaced by
  `subworkflows/nf-core/reshape_samplesheet/main.nf`.
- **`tests/test_*.groovy`** (parser, reshaper, validator, options,
  matching) plus `tests/test_runner.groovy` — retired with the
  bespoke Groovy suite. Behaviour coverage is now via nf-test's
  per-test structural assertions; behavioural edge cases are
  documented in the README's "What the bespoke Groovy suite
  (pre-nf-core) covered" section.

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