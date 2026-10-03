#!/usr/bin/env bash
# Runs the reshape-samplesheet tests.
#
# Three modes:
#   1. nf-test (the nf-core standard) — needs `nf-test` on PATH.
#      Runs the nf-test suite in
#      subworkflows/nf-core/reshape_samplesheet/tests/main.nf.test
#      against the bundled fixtures in tests/data and tests/fastqs.
#      Run with `--nf-test`.
#   2. Smoke test                       — needs `nextflow` on PATH.
#      Runs main.nf end-to-end and verifies the reshaped CSV is written
#      into a throwaway .test_output/ directory. Exercises the same
#      code path the production sub-workflow uses, so confirms
#      Nextflow wires up lib/ correctly. Run with `--smoke`.
#   3. Auto (the default with no flag) — runs the nf-test suite if
#      `nf-test` is on PATH, otherwise falls back to the smoke test.
#      This is what `bin/test.sh` with no arguments does.
#
#   bin/test.sh                # auto: nf-test if available, else smoke
#   bin/test.sh --nf-test      # force the nf-test suite (error if nf-test missing)
#   bin/test.sh --smoke        # force the smoke test
#
# Note: the previous `bin/test.sh --full` mode (79 bespoke Groovy
# unit tests) was retired when the project restructured to the
# nf-core layout. The nf-test suite replaces it with the nf-core-
# standard assertions and snapshot files.
#
# Install nf-test via:
#   pipx install nf-test
#   # or: pip install nf-test
#

set -euo pipefail

cd "$(dirname "$0")/.."

mode="auto"
for arg in "$@"; do
    case "$arg" in
        --nf-test) mode="nf-test" ;;
        --smoke)   mode="smoke"   ;;
        -h|--help)
            sed -n '2,32p' "$0"
            exit 0
            ;;
        *)
            echo "error: unknown argument: $arg" >&2
            exit 2
            ;;
    esac
done

run_nf_test() {
    if ! command -v nf-test >/dev/null 2>&1; then
        cat >&2 <<EOF
error: 'nf-test' not found on PATH.

The nf-test suite needs nf-test installed. Either:
  - Install nf-test (e.g. \`pipx install nf-test\` or \`pip install nf-test\`)
  - Run \`bin/test.sh --smoke\` to fall back to the Nextflow-based smoke test
EOF
        exit 127
    fi
    if [[ ! -d tests/fastqs ]]; then
        echo "==> Generating test fastq fixtures"
        bin/make_test_fastqs.sh tests/fastqs
    fi
    echo "==> Running nf-test suite"
    nf-test test \
        subworkflows/nf-core/reshape_samplesheet/tests/main.nf.test
}

run_smoke() {
    if ! command -v nextflow >/dev/null 2>&1; then
        cat >&2 <<EOF
error: neither 'nf-test' nor 'nextflow' is on PATH.

Install Nextflow from https://www.nextflow.io to run the smoke test,
or install nf-test (https://www.nf-test.com) to run the full suite.
EOF
        exit 127
    fi
    local outdir=".test_output"
    rm -rf "$outdir"
    mkdir -p "$outdir"
    echo "==> Running smoke test (nextflow run main.nf → workDir)"
    # The nf-core module emits the CSV via Nextflow's work-dir staging
    # (publishDir is set by the calling pipeline, not by the module —
    # that's the nf-core convention). The smoke test just verifies
    # the workflow completes and prints the CSV path the sub-workflow
    # emitted.
    local csv_path
    csv_path=$(nextflow -q run main.nf \
        --samplesheet tests/data/illumina_bcl2fastq.csv \
        --fastq_dir   tests/fastqs \
        --outdir      "$outdir" \
        --recursive    false 2>&1 \
        | tee /dev/stderr \
        | grep -E '^Reshaped CSV:' \
        | head -1 \
        | sed 's/^Reshaped CSV: //')
    if [[ -z "$csv_path" ]]; then
        echo "FAIL: sub-workflow did not emit a CSV path" >&2
        exit 1
    fi
    if [[ ! -s "$csv_path" ]]; then
        echo "FAIL: emitted CSV path '$csv_path' is empty or missing" >&2
        exit 1
    fi
    if [[ "$(head -1 "$csv_path")" != "sample,fastq_1,fastq_2" ]]; then
        echo "FAIL: unexpected header in $csv_path" >&2
        head -1 "$csv_path" >&2
        exit 1
    fi
    echo "==> Smoke test passed: $csv_path"
}

case "$mode" in
    nf-test) run_nf_test ;;
    smoke)   run_smoke   ;;
    auto)
        if command -v nf-test >/dev/null 2>&1; then
            run_nf_test
        else
            echo "(nf-test not found — falling back to Nextflow smoke test)"
            run_smoke
        fi
        ;;
esac