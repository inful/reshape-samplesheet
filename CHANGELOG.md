# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.1] - 2026-10-22

Patch release. Five fixes, one refactor, one docs commit since
0.4.0, no breaking changes.

The headline is a **silent data-corruption bug**: a Sample_ID
containing `_R1` or `_R2` caused its own R1 reads to be written into
the `fastq_2` column a second time. The CSV was well-formed and the
run exited cleanly — the wrong reads only surfaced much later, in a
downstream analysis. If you have a sample named like
`patient_R2_relapse` or `cohort_R1_vs_R2`, diff your results before
and after upgrading; the output for those samples changes, because it
is now correct.

Also in this release: `--outdir` now actually receives the CSV (it did
not in 0.3.x or 0.4.0 — the file was stranded in `work/<hash>/`), a
failed run no longer destroys the previous good CSV, paths
containing an apostrophe work, and the output is written as UTF-8.

### Fixed
- **A Sample_ID containing `_R1` or `_R2` silently duplicated reads.**
  Read-direction classification regexed the whole fastq filename,
  sample-ID prefix included. A sample called `case_R2_ctrl` produces
  `case_R2_ctrl_S1_L001_R1_001.fastq.gz`, whose `_R2_` comes from the
  sample name rather than the read direction — so that file was
  classified as **both** R1 and R2, and `fastq_2` carried the R1 reads
  a second time. A well-formed CSV, a clean exit, wrong science.
  Classification now looks only at the part of the name after the
  Sample_ID. Three new tests, one confirming the `_R10` false-positive
  guard still holds.
- **A stray `"` in an unquoted samplesheet cell swallowed every row
  below it, and the error named the wrong line.** `CsvLines
  .splitLogicalLines` toggled "inside a quoted field" on every quote
  character, so a quote in the middle of an unquoted cell opened a
  region that ate the rest of the file. `CsvCell.parseLine` already
  treated such a quote as a literal; the splitter now agrees — the
  same "one RFC-4180 contract" rule applied on the reader side. A
  sheet containing `foo"bar` used to fail with a cell-count error on
  the wrong line; it now parses, with the quote as literal data.
- **Error messages pointed at the wrong row across blank lines.**
  Rows were numbered from the header, but blank lines are filtered out
  before that, so each one shifted the count. A malformed row on line
  5 was reported as line 4. The parser now uses the line number the
  splitter already records on each `CsvLine`.
- **`samplesheet_validate`'s `meta.yml` promised strandedness
  validation that does not exist.** It claimed the value was checked
  as "a sane strandedness label"; `--strandedness banana` passes
  straight through, which `SamplesheetReshape`'s own docs confirm is
  intended. Reworded to match reality.
- **The `-stub` run produced an empty CSV**, so the sub-workflow's
  `samples` emit was silently empty and the stub test — which asserted
  only `workflow.success` — could not tell a working emit from a
  broken one. The stub now writes a header plus one row, and the stub
  test asserts on the emit.
- **My own regression test broke CI on Linux.** `run_output_path_smoke`
  used `md5 -q` to compare the CSV across a failed run. `md5 -q` is the
  BSD/macOS spelling; GNU coreutils ships `md5sum`, which has no `-q`
  flag. The `|| echo none` fallback turned the missing tool into a fake
  failure (`before=none after=GONE`), so `bin/test.sh --smoke` would
  have reported FAIL and turned the whole `test` job red on every push
  to `ubuntu-latest`. It now compares the bytes with POSIX `cmp`.
- **The CSV was written non-atomically.** `File.bytes = ...` truncates
  the destination before writing, so a job killed mid-write (scheduler
  timeout, full disk, node reboot) left a partial CSV where a good one
  used to be. The staging fix protected against validation failure but
  not against interruption. Both copies now go to a `.tmp` name and
  are renamed into place, which is atomic within a directory.
- **`versions.yml` was published into the user's `--outdir`.** The
  `publishDir` directive added for the not-yet-existing-outdir fix
  publishes every output. It is now scoped with
  `pattern: "*.nfcore.csv"`.
- **The modules failed cryptically when `lib/` did not travel with
  them** — a bare `GroovyClassNotFoundException`. They now check for
  the library up front and print where it was looked for and what to
  copy.
- **Stale documentation after the pre-flight removal.** `README.md`,
  `examples/poc/README.md` and a comment in `main.nf` all still
  described an in-process `SamplesheetReshape.validate()` call that no
  longer exists.

### Added
- `subworkflows/nf-core/validate_samplesheet/tests/nextflow.config`,
  mirroring the reshape suite's. Neither root `nextflow.config` nor
  the POC config sets `enable_docker` today, so that suite passed by
  luck; the day someone enables Docker at the root, it would quietly
  start pulling `groovy:4.0-jdk21` while the reshape suite kept using
  system Groovy.
- Vendoring note in the README and in `.nf-core.yml`: all the logic
  lives in the repo-root `lib/`, so `nf-core subworkflows install`
  alone is not sufficient. `lib/` has to come along, or the pipeline
  fails at Nextflow compile time with `` `SamplesheetReshape` is not
  defined ``.

### Changed
- **`--outdir` never received the CSV.** `SAMPLESHEET_RESHAPE` wrote
  the file into the staged `output_dir` and then `mv`'d it into the
  task workdir so Nextflow's output glob could capture it. Nothing
  published it back, so the user's chosen directory stayed empty and
  the CSV was stranded in `work/<hash>/`. The script now copies rather
  than moves, and a `publishDir` directive publishes to
  `params.outdir` — which is also what makes a **not-yet-existing**
  `--outdir` work, since the `output_dir` input is staged as a broken
  symlink in that case and the script's `mkdir` created a workdir-local
  directory instead. The existing smoke test did not catch this
  because it only asserted on the workdir path echoed in the log.
- **A failed run destroyed the previous good CSV.** The script deleted
  the existing CSV before validating, so a run that then threw (e.g.
  a `--fastq_dir` with no matching fastqs) left the user with no
  output at all. The reshape now stages into a workdir-local directory
  and only places the file after it succeeds. Regression-tested by
  `run_output_path_smoke` in `bin/test.sh --smoke`.
- **The output CSV was written in the platform's default character
  encoding** while the reader used UTF-8 explicitly, so a non-ASCII
  sample name was silently destroyed on any JVM not defaulting to
  UTF-8 (older Docker images, `LANG=C` containers). Verified: with
  `-Dfile.encoding=ISO-8859-1`, `sämple_X` previously came back as
  `s?mple_X`. Now written with `setText(..., 'UTF-8')`, with a unit
  test that fails if the default charset is used again.
- **Any apostrophe in a path broke the reshape job.** The module
  interpolated user paths straight into single-quoted bash, so
  `--outdir "/tmp/O'Brien_out"` or a samplesheet named
  `O'Brien_sheet.csv` produced `syntax error near unexpected token
  ')'`. All three modules now shell-quote every interpolated value
  (POSIX-safe: wrap in single quotes, escape embedded apostrophes)
  and pass paths to the Groovy entry point as command-line arguments
  rather than pasting them in as string literals, which also fixes the
  Groovy-side escaping.
- **An unreadable `fastq_dir` reported "contains no fastq files"**
  instead of the real cause. It now says the directory is not readable.

- **Removed the duplicate in-process pre-flight** in `main.nf` and
  `examples/poc/main.nf`. Both called `SamplesheetReshape.validate()`
  before invoking the sub-workflow, whose `SAMPLESHEET_VALIDATE`
  module does the same work — so the full sample-to-fastq matching ran
  twice per invocation. This was only safe to remove *after* fixing
  the data-loss bug above, which is what the extra copy had been
  masking.
- **`examples/poc/bin/setup.sh` now always re-copies `lib/`.** It
  previously skipped the copy when the destination existed, so the POC
  silently ran against a stale snapshot: the staged copy was missing
  `lib/CsvCell.groovy` entirely and had four divergent files, meaning
  the POC proved nothing about the current library.
- `SECURITY.md` support table updated to `0.4.x` / `< 0.4.0`. It still
  listed `0.1.x` as supported, contradicting the sentence above it and
  having never been updated for 0.2.x, 0.3.x or 0.4.0.
- `examples/poc/main.nf` now uses the same `boolParam` coercion helper
  as the root entry point instead of its own inline copy.
- Documented that `validate()` is not cheaper than `reshape()` — it
  runs the whole reshape and discards the output.

### Known limitations
- **`samplesheet_validate` and `samplesheet_validate_bcl2fastq` are
  still near-duplicates** (38 substantive shared lines). Merging them
  needs a process input that is absent for the pre-bcl2fastq case,
  which on Nextflow 26.10.3 means `path x, optional: true` — and that
  fails to parse (`No such variable: optional`). A `Channel.value(null)`
  placeholder fails too. The merge was attempted and reverted; until a
  supported optional-input syntax exists, changes to one must be made
  to the other.
- The O(samples x fastq_files) matching and the O(n^2) index-pair
  check were measured rather than assumed: 1,000 samples against
  4,000 fastqs takes 1.24 s, and 2,000 samples takes 1.42 s for the
  index check alone. Acceptable at the expected load (hundreds to low
  thousands of samples); only worth optimising if a much larger run is
  reported.
- `validate_samplesheet` still lists one component in its `meta.yml`.
  nf-core's real `subworkflows lint` requires ≥2 modules per
  sub-workflow; the repo's CI check does not enforce it.

## [0.4.0] - 2026-10-22

Minor release. Two `feat` commits since 0.3.1 add public surface — a
new sub-workflow and a new CLI flag — so this is a minor bump per
SemVer. No breaking changes: every change is additive or a bug fix.

The headline is that `--fastq_dir` is no longer a hard requirement for
the pre-demultiplexing use case. Previously the only way to check that
an Illumina samplesheet is demultiplexable was to drop into Groovy,
because both `main.nf` and the `reshape_samplesheet` sub-workflow
unconditionally required a directory of fastqs that do not exist until
after bcl2fastq has run.

### Added
- **`--validate-only` flag on `main.nf`** — the pre-bcl2fastq gate is
  now reachable from the CLI, not just the sub-workflow. When set,
  only `--samplesheet` is required: the `--fastq_dir`, `--outdir`, and
  `--recursive` requirements are skipped and the run performs the
  bcl2fastq structural checks in-process, then stops. Companion flag
  `--hamming-distance-as-error` promotes Hamming-distance index
  violations from warning to error.
  ```bash
  nextflow run main.nf --samplesheet SampleSheet.csv --validate-only
  ```
  Both flags accept either spelling (`--validate-only` /
  `--validate_only`) because Nextflow normalises CLI param names
  asymmetrically — dashed flags arrive camelCased as
  `params.validateOnly`, underscored flags arrive as
  `params.validate_only`. Supporting only one would mean a caller who
  guessed the other spelling silently falls through to the reshape
  path and gets a confusing `Missing required parameter: --fastq_dir`.
- New `bin/test.sh` coverage: a `run_validate_only_smoke` step, wired
  into `--smoke` mode, that asserts both flag spellings accept a good
  samplesheet and that a bad one fails naming both the invalid I7
  (`ATXGACGT`) and the duplicate I7+I5 combination.

### Changed
- **New `validate_samplesheet` sub-workflow** — pre-demultiplexing
  validation of an Illumina samplesheet. Takes **no `fastq_dir`**, so
  it works before bcl2fastq/bclconvert has produced anything, which
  is the case the existing `reshape_samplesheet` sub-workflow could
  not serve (it requires the fastqs to already be on disk). Checks
  Sample_ID uniqueness, non-empty I7/I5 values, valid index sequences
  (A/C/G/T/N), consistent I7/I5 lengths, and unique I7+I5
  combinations — all collected into a single exception so the whole
  samplesheet can be fixed in one pass. Hamming distance between
  same-column indices is a warning by default; opt in to
  `hammingDistanceAsError: true` to make it fatal.
- **New `samplesheet_validate_bcl2fastq` module** — the nf-core
  module behind the new sub-workflow. Thin wrapper around
  `SamplesheetReshape.validateBcl2fastq()`, the lib entry point that
  already existed for this exact use case but was only reachable by
  dropping into Groovy.
- **CSV cell quoting/escaping consolidated in a shared `lib/CsvCell.groovy`**.
  Three sites — input parsing (`SamplesheetParser`), output parsing
  (`ReshapedCsvParser`), and output writing (`SamplesheetReshaper`) —
  now all route through one RFC-4180 contract. Previously the
  writer's `joinPaths` ad-hoc helper and the input parser's private
  `parseCsvLine` had duplicated cell-handling logic that drifted
  twice in the 0.3.0 release (multi-line cells, multi-value cells).
  The new `CsvCell.quote` and `CsvCell.parseLine` are the single
  source of truth and are tested in isolation.
- `nextflow.config` now **declares** the two optional flags with an
  explicit `null` (in both spellings) instead of leaving them
  undefined. A declaration is not a default — an omitted flag still
  reads as null and `boolParam` still returns false — but it stops
  Nextflow printing a `WARN: access to undefined parameter` line on
  every run.

### Fixed
- **Writer now RFC-4180-quotes every cell that needs it, including the
  Sample_ID and strandedness columns.** Previously the writer
  quoted multi-value cells (multiple R1/R2 paths joined with `,`)
  via the `joinPaths` helper but emitted the sample-id cell and the
  strandedness cell raw — so a Sample_ID containing `,` (e.g.
  `study_X,patient_Y`) was emitted as
  `study_X,patient_Y,/r1,/r2`, and the parser on the receiving
  side (the sub-workflow's `samples` emit and the
  `SamplesheetReshape.parseReshapedCsv` public API) silently
  truncated `meta.id` to `study_X`, with the rest of the row
  falling off into phantom columns. The writer now wraps any cell
  containing `,`, `"`, `\n`, or `\r` per RFC-4180 (with `"` doubled
  to `""` inside the quoted region), and an end-to-end regression
  test locks the round-trip. Behaviour change for previously-unique
  paths in the r1/r2 cells that contain a comma: those single-path
  cells are now also quoted. (Single-path cells with no special
  character are still emitted unquoted, matching every nf-core
  pipeline's samplesheet convention.)
- **`bin/test.sh --nf-test` now runs every sub-workflow suite.** It
  previously hardcoded the reshape suite's path, so any other
  sub-workflow's nf-test cases would silently never run in CI.

### Known limitations
- `validate_samplesheet` lists **one** component in its `meta.yml`.
  nf-core's real `subworkflows lint` requires ≥2 modules per
  sub-workflow. The repo's CI structural check does not enforce this
  (it counts every list item in the file, not just `components:`), so
  CI is green — but submission to nf-core/modules will need the
  sub-workflow split or a second module added.

## [0.3.1] - 2026-10-05

### Fixed
- **Reshape script no longer wipes the whole `output_dir`.** The
  `SAMPLESHEET_RESHAPE` script's `rm -rf "${output_dir}"` was
  removing any other files the user had placed in the output
  directory (logs, a summary report, a different CSV from a
  parallel run) on every reshape. The script now only removes
  the specific CSV it's about to regenerate, and only clears a
  pre-existing path at `output_dir` if it isn't already a
  directory (the test-fixture case where Nextflow's
  `checkIfExists: false` stages a symlink to a non-existent
  target). A new nf-test case
  (`reshape preserves sibling files in output_dir`) pre-creates
  a sentinel file in the output directory and asserts it
  survives the reshape — a future regression to `rm -rf` would
  fail this test.

## [0.3.0] - 2026-10-05

### Added
- **BCLConvert (V2) samplesheet format support.** The parser
  now auto-detects BCLConvert V2 sheets (with `[BCLConvert_Data]`
  or `[Cloud_Data]` sections) in addition to bcl2fastq V1 (with
  `[Data]`) and LRM (no section markers). V2 takes precedence
  over V1 if both are present (a malformed file would have
  both, but the V2 data section is the one BCLConvert reads).
  V2-specific sections like `[Header]` (FileFormatVersion,
  RunName) and `[BCLConvert_Settings]` (SoftwareVersion,
  OverrideCycles) are recognised for auto-detection but not used
  by the reshape use case — they don't leak into sample records.
  The bcl2fastq structural validator (Sample_ID uniqueness, I7/I5
  format, length consistency, I7+I5 uniqueness, Hamming distance)
  works unchanged on V2 records because the per-sample structure
  is identical to V1. New fixture `tests/data/illumina_bclconvert_v2.csv`
  with 4 samples in the NovaSeq X series layout; 5 new unit
  tests in `tests/test_parser.groovy` cover V2 happy path,
  section-silencing, `[Cloud_Data]` alternative, validator
  compatibility, and the end-to-end reshape round-trip.
- **Hamming distance check in the bcl2fastq validator.** For
  every pair of indices in the same column (I7 and I5), the
  validator now flags any pair whose Hamming distance is below
  2 (the minimum at which a single sequencing error cannot
  cross-assign reads between two samples). Skips pairs already
  caught by other checks (identical indices → I7+I5 combination
  check; empty or unequal-length indices → their respective
  checks). 7 new unit tests in `tests/test_validator.groovy`
  cover I7, I5, identical-pair de-duplication, unequal-length
  handling, empty-cell handling, lowercase normalisation, and
  the just-above-the-limit pass case.
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

### Changed
- **Hamming distance check is a warning by default, not an
  error.** A Hamming distance of 1 is a soft risk that bcl2fastq
  may or may not handle depending on the configured mismatch
  tolerance, and some NovaSeq X series UMI-style demultiplexing
  workflows deliberately use close indices. So the new check
  now prints violations to `System.err` and continues by
  default — the pipeline doesn't abort. To promote Hamming
  violations to errors (so they participate in the
  all-issues-in-one-exception contract), pass
  `hammingDistanceAsError: true` in the `opts` map on
  `reshape(samplesheet, fastq_dir, opts)` or
  `validateBcl2fastq(samplesheet, opts)`.
- **Multi-line quoted CSV cells are now read correctly.** The
  ReshapedCsvParser used to split on `\n` directly, which broke
  on any CSV that had a multi-line cell (a small but real RFC
  4180 feature). The parser now uses a quote-aware line
  splitter (extracted to the shared `lib/CsvLines.groovy`); the
  unclosed-quote error message now includes the source line
  number.
- **Multi-value CSV cells are now RFC-4180 quoted.** The reshape
  writer previously wrote comma-joined multi-value cells
  unquoted, which made the CSV unparseable by `splitCsv` and
  any standards-compliant reader. The writer now wraps any
  cell containing `,` in double quotes; single-value cells are
  left unquoted. Without this fix, the new `out.samples`
  emit and `parseReshapedCsv` API wouldn't work end-to-end.

### Fixed
- **Internal: dead `V2_SECTION_MARKERS` and
  `V2_FILE_FORMAT_VERSION_HEADER` constants** in
  `lib/SamplesheetParser.groovy` were defined for an earlier V2
  detection design and never used. Removed.
- **Internal: `SamplesheetReshape.parseReshapedCsv(Object, Map)`
  YAGNI overload** that accepted an `opts` map and ignored it
  was removed (a public method that silently ignores its
  arguments is worse than no overload at all). The single-arg
  `parseReshapedCsv(Object)` is the public API.
- **Docs: stale `.gitignore` comment for `results-poc/`** said
  the POC wrote merged fastq.gz there via CAT_FASTQ; the POC
  now uses nf-core/fastqc and only the reshaped CSV goes to
  `results-poc/` (fastqc HTML/zip go to `work/`).
- **Docs: `bin/test.sh` header** said "Four modes" but listed
  six — fixed.
- **Docs: root README** now documents `hammingDistanceAsError`,
  the Hamming check itself, and the "Input handling" table
  includes a row for Hamming violations.
- **Docs: POC README** Caveats section now reflects the lib's
  current coverage (V1/V2 + Hamming) vs what's still missing
  (adapter detection, V1↔V2 conversion, OverrideCycles).

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