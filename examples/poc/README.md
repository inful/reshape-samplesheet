# POC: reshape-samplesheet → nf-core/fastqc

A proof of concept that demonstrates this project's reshape sub-workflow
can be fed straight into an existing nf-core module. The sub-workflow
emits an Illumina samplesheet in the generic nf-core layout
(`sample,fastq_1,fastq_2`); the POC runs `nf-core/fastqc` on the
per-sample reads to produce QC reports for every sample — including
the multi-lane `sample_A`, which produces one report per input file
(2 R1 lanes + 2 R2 lanes = 4 reports).

The value proposition is the **three-line integration**: a consumer
goes from "I have an Illumina samplesheet" to "I have FastQC reports
for every sample" with a 3-line channel pattern. No inline CSV
parser, no RFC-4180 handling, no file matching logic — the
sub-workflow exposes a pre-parsed `samples` emit (a channel of
`[meta, fastq_1, fastq_2]` tuples) that drops straight into
`FASTQC`'s input contract.

## Why this exists

Three things this POC validates:

1. **The `samples` emit is consumable by a real analysis module.**
   The end-to-end story is "Illumina bcl2fastq samplesheet → reshaped
   CSV → parsed (meta, reads) tuples → FastQC reports". Each step
   is exercised by real code (no stubbed fastqc, no skipped
   validation).
2. **The comma-joined multi-lane cells round-trip through the
   parser.** A multi-lane sample's `fastq_1` cell is
   `"/path/lane1.fq,/path/lane2.fq"` after reshape; `ReshapedCsvParser`
   in the main lib parses it back into a `List<Path>`, the
   sub-workflow exposes it as `out.samples[1]`, and the POC's
   smoke test asserts that `sample_A`'s fastqc invocation receives
   BOTH lanes (verified by inspecting the `printf` line in the
   process's `.command.sh`).
3. **The pre-flight validation actually fires.** `SamplesheetReshape.validate()`
   runs synchronously before any process is submitted, so a
   malformed samplesheet or a missing fastq fails with a complete
   error message before the user pays for any compute.

## Quick start

```bash
# One-time: stage lib/ + clone nf-core/modules@<pinned SHA>
examples/poc/bin/setup.sh

# Run the POC end-to-end (uses Docker to run fastqc; see "Docker
# requirement" below for how to override)
nextflow run examples/poc/main.nf \
    --samplesheet tests/data/illumina_bcl2fastq.csv \
    --fastq_dir   tests/fastqs \
    --outdir      results-poc \
    --recursive    false
```

Or, from the repo root with the test runner:

```bash
bin/test.sh --poc-smoke    # end-to-end: real fastqc against the test fixtures
bin/test.sh --poc-test     # full stack: nf-test (stub) + the real fastqc smoke
```

The smoke test asserts:
- All 4 samples (sample_A, sample_B, sample_C, sample_D) have non-empty FastQC HTML reports
- `sample_A` produces 4 reports (one per input file: 2 R1 lanes + 2 R2 lanes), proving the multi-lane aggregation made it into FastQC's input list
- Both `L001_R1` and `L002_R1` appear in `sample_A`'s FastQC `.command.sh`, proving the per-lane files weren't dropped at the boundary

## Layout

```
examples/poc/
├── README.md                          # this file
├── main.nf                            # CLI entry point (params → channels)
├── poc.nf                             # Named sub-workflow POC_RESHAPE_AND_FASTQC
├── nextflow.config                    # enables Docker by default
├── bin/
│   └── setup.sh                       # one-time: stages lib/ + clones nf-core/modules
├── lib/                               # gitignored, populated by setup.sh
│   ├── ReshapedCsvParser.groovy      # RFC-4180 reader for the lib's own output
│   ├── SamplesheetReshape.groovy      # the public reshape API
│   ├── SamplesheetReshaper.groovy     # the reshape implementation
│   ├── SamplesheetParser.groovy       # INPUT samplesheet parser (bcl2fastq/LRM)
│   ├── SamplesheetValidator.groovy    # bcl2fastq structural validation
│   └── CsvSupport.groovy              # path utilities
└── tests/
    └── main.nf.test                   # nf-test in stub mode (structural wire)
```

The split between `main.nf` and `poc.nf` follows the nf-core
convention for include-able sub-workflows:

- **`poc.nf`** is a named sub-workflow (`workflow POC_RESHAPE_AND_FASTQC`)
  with `take:` / `main:` / `emit:` blocks. This is what nf-test
  references (`script "../poc.nf"`, `workflow "POC_RESHAPE_AND_FASTQC"`)
  and what another pipeline would `include` to use the POC
  composition in their own context.
- **`main.nf`** is a thin CLI wrapper that reads params, calls
  `SamplesheetReshape.validate(...)` for the synchronous pre-flight
  check, and translates each scalar param into a single-element
  channel so the named sub-workflow can be invoked with the
  standard channel pattern.

## The three-line benefit

The whole point of the new `out.samples` emit on
`RESHAPE_SAMPLESHEET` is that the consumer code in the named
sub-workflow is three lines:

```groovy
RESHAPE_SAMPLESHEET.out.samples
    .map { meta, fq1, fq2 -> [meta, fq1 + fq2] }
    .set { ch_fastqc_input }
FASTQC(ch_fastqc_input)
```

That's it. No inline CSV parser, no RFC-4180 handling, no
`splitCsv` workaround, no file matching — the sub-workflow
already did all of that and exposed the result as ready-to-use
tuples. The single `.map` adapts the shape to FastQC's input
contract (`[meta, [Path, ...]]` with all reads concatenated into
one list).

## One-time setup

`examples/poc/bin/setup.sh` does two things (both idempotent —
re-running is safe and fast):

1. **Stages `lib/` from the repo root** into
   `examples/poc/lib/`. The POC's `projectDir` is
   `examples/poc/`, not the repo root, so Nextflow's auto-classpath
   only loads files from `examples/poc/lib/`. The lib code is
   copied (not symlinked) so the POC is self-contained.
   **Re-run `setup.sh` after any change to the main `lib/`** —
   the staged copy is not automatic.
2. **Clones `nf-core/modules` at a pinned SHA** into
   `examples/poc/nf-core-modules/`. The `include { FASTQC }`
   path in `poc.nf` points into this clone, so the version of
   `fastqc` is fully reproducible. Bump `NFCORE_MODULES_SHA` in
   `setup.sh` to pick up upstream changes.

Both directories are in `.gitignore`.

## Docker requirement

The POC's `nextflow.config` enables `docker.enabled = true` by
default, which means the FastQC process pulls its container from
`quay.io/biocontainers/fastqc:0.12.1` on first run. This is the
"just works" path for most users with Docker installed. If you'd
rather use a local FastQC:

- Install `fastqc` on `PATH` and run with `-profile standard` (or
  override with `docker.enabled = false` on the command line)
- Or use a conda env: the FastQC process has a `conda` directive

The smoke test (`bin/test.sh --poc-smoke`) requires either Docker
or a local FastQC. It will fail with a clear "fastqc: command
not found" if neither is available.

## What the test layers cover

| Layer | Tool | Asserts | Needs on PATH |
|---|---|---|---|
| Parser unit | `bin/test.sh --unit` (includes 11 new tests in `tests/test_reshaped_csv_parser.groovy`) | 11 cases: happy path, RFC-4180 quoted multi-value cells, edge cases (BOM, CRLF, escaped quotes), empty / malformed input, end-to-end against the lib's actual output | `groovy` |
| Wire + structure | `bin/test.sh --poc-test` (includes the nf-test step) | nf-test in stub mode: `workflow.success` + the emits are declared with the right shape | `nf-test` + `nextflow` + `groovy` |
| End-to-end real | `bin/test.sh --poc-smoke` | Runs the real `fastqc` (via Docker) against the test fixtures and asserts every sample (including the multi-lane `sample_A`) has non-empty HTML reports | `nextflow` + `groovy` + (Docker OR a local `fastqc`) |

The unit tests cover the parser in isolation; the nf-test
verifies the include / channel wiring; the smoke test verifies
the end-to-end real behaviour. The three-line consumer pattern
lives in `poc.nf` and is exercised by both the nf-test (the
shape of `ch_fastqc_input`) and the smoke test (the real
FastQC invocation and per-sample reports).

## Caveats and extension points

- **Lane ordering**: the reshape lib uses `File.listFiles()` order
  (filesystem-dependent) for the per-direction file lists. For
  the common bcl2fastq layout (`..._L00{Lane}_R{1,2}_...`) the
  order is consistent between R1 and R2 cells. Consumers that
  need pairwise correspondence by lane should re-sort by lane
  number explicitly before zipping — out of scope for this POC.
- **FastQC input naming**: FastQC appends `_fastqc.html` to each
  input file's basename and adds an index suffix when there are
  multiple inputs. The smoke test accounts for this (`${s}_*_fastqc.html`).
- **No `samplesheetparser/validate` dependency**: by design. The
  POC relies on the lib's built-in `validate()` and
  `validateBcl2fastq()` for validation. The lib has full
  coverage of what `samplesheetparser/validate` covers for
  *V1/V2 bcl2fastq parsing* (Sample_ID uniqueness, I7/I5 format
  and length, I7+I5 uniqueness, Hamming distance), without
  taking on the Python dependency. Still-missing from the
  lib (and therefore from this POC): adapter sequence
  detection, bidirectional V1↔V2 conversion, and
  OverrideCyles parsing for UMI extraction. None of these
  are needed for the reshape use case (Sample_ID → fastq
  files), so they're out of scope.
- **Single module today**: this POC includes only `nf-core/fastqc`.
  Adding another downstream module (e.g. `nf-core/fastp`,
  `nf-core/cat`) is a matter of adding the include to `poc.nf`
  and wiring a similar `.map` from the `samples` emit.
- **`strandedness` is not propagated**: FastQC doesn't read the
  `strandedness` column, so the POC's default 3-column CSV is
  used. To add `strandedness`, pass `--strandedness unstranded`
  (or `forward` / `reverse` / `auto`) and the lib will add the 4th
  column; downstream modules that read it would need their own
  read from the CSV (the `samples` emit doesn't currently
  include the `strandedness` value).
