#!/usr/bin/env bash
# Runs the reshape-samplesheet tests.
#
# Four modes:
#   1. unit      — bespoke Groovy unit tests of every parser/reshape/
#                  validate/opts-coercion/matching edge case. Tests
#                  the lib directly (no Nextflow required). Fast:
#                  finishes in ~10s. Needs `groovy` on PATH.
#   2. nf-test   — nf-core nf-test suite that exercises the full
#                  sub-workflow emit path. Needs `nf-test` + `nextflow`.
#                  Finish in ~30s.
#   3. smoke     — Nextflow end-to-end via main.nf. Needs `nextflow`.
#   4. auto      — runs unit + nf-test if nf-test is on PATH, else
#                  unit + smoke. Default when called with no flags.
#
#   bin/test.sh                # auto
#   bin/test.sh --unit         # just the 79 lib unit tests
#   bin/test.sh --nf-test      # just nf-test
#   bin/test.sh --smoke        # just the Nextflow smoke test
#
# Why both unit AND nf-test?
#   unit      = fast, cheap, in-process — catches every parser /
#               validate / matching / coercion edge case at the lib
#               level. Doesn't exercise the Nextflow wire.
#   nf-test   = slow, full integration — catches module-wrapping,
#               bash/Groovy subprocess, Nextflow path staging, and
#               emit-channel regressions. Doesn't check every semantic
#               case.
# Both are needed; together they give complete coverage.
#
# Install nf-test via:
#   curl -fsSL https://get.nf-test.com | bash
#   # or: pipx install nf-test

set -euo pipefail

cd "$(dirname "$0")/.."

mode="auto"
for arg in "$@"; do
    case "$arg" in
        --unit)    mode="unit"    ;;
        --nf-test) mode="nf-test" ;;
        --smoke)   mode="smoke"   ;;
        -h|--help)
            sed -n '2,40p' "$0"
            exit 0
            ;;
        *)
            echo "error: unknown argument: $arg" >&2
            exit 2
            ;;
    esac
done

run_unit() {
    if ! command -v groovy >/dev/null 2>&1; then
        cat >&2 <<EOF
error: 'groovy' not found on PATH.

The unit-test suite needs a standalone Groovy install (separate
from the Groovy that ships inside Nextflow's fat JAR).
Install with one of:
  - SDKMAN:  sdk install groovy
  - Homebrew: brew install groovy
  - mise:    mise use --yes groovy@latest
EOF
        exit 127
    fi
    local total_exit=0
    local total_pass=0
    local total_fail=0
    # test_runner.groovy is the harness loaded via `evaluate` by the
    # other tests — skip it as a standalone test.
    for test_file in tests/test_*.groovy; do
        [[ "$(basename "$test_file")" == "test_runner.groovy" ]] && continue
        echo "--- $test_file"
        # Capture output so we can tally pass/fail totals across files.
        local out
        if out=$(groovy -cp lib "$test_file" 2>&1); then
            local last
            last=$(echo "$out" | grep "^Results:" | tail -1)
            echo "$last"
            local passed failed
            passed=$(echo "$last" | sed -E 's/.* ([0-9]+) passed.*/\1/')
            failed=$(echo "$last" | sed -E 's/.* ([0-9]+) failed.*/\1/')
            total_pass=$((total_pass + passed))
            total_fail=$((total_fail + failed))
        else
            echo "$out" | tail -5
            total_fail=$((total_fail + 1))
            total_exit=1
        fi
    done
    echo ""
    echo "Unit tests total: ${total_pass} passed, ${total_fail} failed"
    return $total_exit
}

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
    unit)
        run_unit
        ;;
    nf-test)
        run_unit
        run_nf_test
        ;;
    smoke)
        run_unit
        run_smoke
        ;;
    auto)
        # Always run the cheap unit tests first; they don't need
        # Nextflow or nf-test, so they catch most regressions in seconds.
        run_unit
        if command -v nf-test >/dev/null 2>&1; then
            run_nf_test
        elif command -v nextflow >/dev/null 2>&1; then
            echo "(nf-test not found — falling back to Nextflow smoke test)"
            run_smoke
        else
            echo "(nf-test and nextflow both missing — skipping integration tests)"
        fi
        ;;
esac