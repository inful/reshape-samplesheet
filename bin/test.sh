#!/usr/bin/env bash
# Runs the SamplesheetReshape tests.
#
# Three modes:
#   1. Full unit test suite (79 tests across 5 test files) — needs `groovy` on PATH.
#      Runs every tests/test_*.groovy file (excluding test_runner.groovy, which
#      is loaded via `evaluate` by the others) against the bundled fixtures in
#      tests/data and tests/fastqs.
#   2. Smoke test                       — needs `nextflow` on PATH.
#      Runs main.nf end-to-end and verifies the reshaped CSV is written
#      into a throwaway .test_output/ directory. This exercises the
#      same code path the production sub-workflow uses, so it confirms
#      Nextflow wires up lib/ correctly. Run with `--smoke`.
#   3. Auto (the default with no flag) — runs the full suite if `groovy` is on
#      PATH, otherwise falls back to the smoke test. This is what `bin/test.sh`
#      with no arguments does.
#
#   bin/test.sh                # auto: full suite if groovy is available, else smoke
#   bin/test.sh --full         # force the full suite (error if groovy missing)
#   bin/test.sh --smoke        # force the smoke test
#
# Install Groovy alongside your existing Java + Nextflow:
#   sdk install groovy                # SDKMAN
#   brew install groovy               # Homebrew
#   mise use --yes groovy@latest      # mise (also pins java 21 for Nextflow)

set -euo pipefail

cd "$(dirname "$0")/.."

mode="auto"
for arg in "$@"; do
    case "$arg" in
        --full)  mode="full"  ;;
        --smoke) mode="smoke" ;;
        -h|--help)
            sed -n '2,22p' "$0"
            exit 0
            ;;
        *)
            echo "error: unknown argument: $arg" >&2
            exit 2
            ;;
    esac
done

run_full() {
    if ! command -v groovy >/dev/null 2>&1; then
        cat >&2 <<EOF
error: 'groovy' not found on PATH.

The full unit test suite needs a standalone Groovy install. Either:
  - Install Groovy (e.g. \`sdk install groovy\`, \`brew install groovy\`,
    or \`mise use groovy@latest\`)
  - Run \`bin/test.sh --smoke\` to fall back to the Nextflow-based smoke test
EOF
        exit 127
    fi
    if [[ ! -d tests/fastqs ]]; then
        echo "==> Generating test fastq fixtures"
        bin/make_test_fastqs.sh tests/fastqs
    fi
    echo "==> Running full unit test suite with groovy"
    local total_exit=0
    for test_file in tests/test_*.groovy; do
        # Skip test_runner.groovy — it's loaded via `evaluate` by the
        # other test files, not run on its own.
        [[ "$(basename "$test_file")" == "test_runner.groovy" ]] && continue
        echo "--- $test_file"
        groovy -cp lib "$test_file" || total_exit=1
    done
    return $total_exit
}

run_smoke() {
    if ! command -v nextflow >/dev/null 2>&1; then
        cat >&2 <<EOF
error: neither 'groovy' nor 'nextflow' is on PATH.

Install Nextflow from https://www.nextflow.io to run the smoke test,
or install Groovy to run the full test suite.
EOF
        exit 127
    fi
    local outdir=".test_output"
    rm -rf "$outdir"
    echo "==> Running smoke test (nextflow run main.nf → $outdir/)"
    # Use the default 3-column output (no --strandedness). The lib's
    # strandedness coercion is unit-tested separately; the smoke test
    # just confirms the pipeline runs end-to-end.
    nextflow -q run main.nf \
        --samplesheet tests/data/illumina_bcl2fastq.csv \
        --fastq_dir   tests/fastqs \
        --outdir      "$outdir" \
        --recursive    false
    local csv="$outdir/illumina_bcl2fastq.nfcore.csv"
    if [[ ! -s "$csv" ]]; then
        echo "FAIL: expected $csv to exist and be non-empty" >&2
        exit 1
    fi
    if [[ "$(head -1 "$csv")" != "sample,fastq_1,fastq_2" ]]; then
        echo "FAIL: unexpected header in $csv" >&2
        head -1 "$csv" >&2
        exit 1
    fi
    echo "==> Smoke test passed: $csv"
}

case "$mode" in
    full)  run_full  ;;
    smoke) run_smoke ;;
    auto)
        if command -v groovy >/dev/null 2>&1; then
            run_full
        else
            echo "(groovy not found — falling back to Nextflow smoke test)"
            run_smoke
        fi
        ;;
esac
