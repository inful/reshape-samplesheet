#!/usr/bin/env bash
# Runs the reshape-samplesheet tests.
#
# Six modes:
#   1. unit      — bespoke Groovy unit tests of every parser/reshape/
#                  validator/opts-coercion/matching edge case plus the
#                  ReshapedCsvParser. Tests the lib directly (no Nextflow
#                  required). Fast: finishes in ~10s. Needs `groovy` on PATH.
#   2. nf-test   — nf-core nf-test suite that exercises the full
#                  sub-workflow emit path (including the `samples` emit).
#                  Needs `nf-test` + `nextflow`. Finishes in ~30s.
#   3. smoke     — Nextflow end-to-end via main.nf. Needs `nextflow`.
#   4. auto      — runs unit + nf-test if nf-test is on PATH, else
#                  unit + smoke. Default when called with no flags.
#                  Also runs the POC smoke if
#                  examples/poc/bin/setup.sh has been executed.
#
# Plus two POC-specific modes for the reshape → nf-core/fastqc proof
# of concept at examples/poc/:
#   5. poc-smoke — End-to-end POC: real fastqc against the test
#                  fixtures. Asserts every sample (including the
#                  multi-lane sample_A) gets a non-empty HTML report.
#                  Needs `nextflow` on PATH.
#   6. poc-test  — Full POC stack: nf-test in stub mode + the real
#                  fastqc smoke. Needs `nextflow` + `nf-test` + the
#                  setup.sh clone.
#
#   bin/test.sh                # auto
#   bin/test.sh --unit         # just the lib unit tests
#   bin/test.sh --nf-test      # just the reshape_samplesheet nf-test
#   bin/test.sh --smoke        # just the root main.nf smoke test
#   bin/test.sh --poc-smoke    # just the POC end-to-end fastqc smoke
#   bin/test.sh --poc-test     # the full POC stack (nf-test + smoke)
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
        --unit)      mode="unit"      ;;
        --nf-test)   mode="nf-test"   ;;
        --smoke)     mode="smoke"     ;;
        --poc-smoke)  mode="poc-smoke"  ;;
        --poc-test)   mode="poc-test"   ;;
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

# POC-specific helpers ---------------------------------------------------------
# The POC at examples/poc/ has two self-contained test layers:
#   - examples/poc/tests/main.nf.test — nf-test in stub mode
#     to verify the include wire end-to-end (RESHAPE_SAMPLESHEET
#     → samples emit → FASTQC) without shelling out to fastqc
#   - run_poc_smoke                    — Nextflow end-to-end that
#     runs the real fastqc against the test fixtures
#
# Both depend on `examples/poc/bin/setup.sh` having been run at
# least once (clones nf-core/modules, stages lib/ from the repo
# root). The ReshapedCsvParser used to be POC-local; it now lives
# in the main lib and is tested by the main unit suite, so there's
# no POC-specific unit layer anymore.

run_poc_setup() {
    if [[ ! -d examples/poc/lib || ! -d examples/poc/nf-core-modules ]]; then
        echo "==> Running examples/poc/bin/setup.sh (one-time)"
        examples/poc/bin/setup.sh
    else
        echo "==> examples/poc/lib and examples/poc/nf-core-modules already present, skipping setup"
    fi
}

run_poc_nf_test() {
    if ! command -v nf-test >/dev/null 2>&1; then
        cat >&2 <<EOF
error: 'nf-test' not found on PATH.

The POC nf-test suite needs nf-test installed. Either install nf-test
(see the comment at the top of bin/test.sh) or skip --poc-test.
EOF
        exit 127
    fi
    if [[ ! -d tests/fastqs ]]; then
        echo "==> Generating test fastq fixtures"
        bin/make_test_fastqs.sh tests/fastqs
    fi
    echo "==> Running POC nf-test suite (stub mode)"
    nf-test test examples/poc/tests/main.nf.test
}

run_poc_smoke() {
    if ! command -v nextflow >/dev/null 2>&1; then
        cat >&2 <<EOF
error: 'nextflow' not found on PATH.

The POC smoke test needs Nextflow installed. See the comment at the
top of bin/test.sh.
EOF
        exit 127
    fi
    if [[ ! -d tests/fastqs ]]; then
        echo "==> Generating test fastq fixtures"
        bin/make_test_fastqs.sh tests/fastqs
    fi
    local outdir="results-poc"
    rm -rf "$outdir"
    rm -rf work
    echo "==> Running POC smoke test (nextflow run examples/poc/main.nf → workDir)"
    nextflow -q run examples/poc/main.nf \
        --samplesheet tests/data/illumina_bcl2fastq.csv \
        --fastq_dir   tests/fastqs \
        --outdir      "$outdir" \
        --recursive    false 2>&1 \
        | tee /dev/stderr \
        | grep -E '(FastQC|SUCCESS|FAIL|ERROR)' \
        | tail -20
    # Spot-check: every sample's fastqc HTML report must exist
    # and be non-empty (proves the real fastqc ran on every
    # sample, including the multi-lane sample_A).
    #
    # fastqc's output naming: it appends `_fastqc.html` to the
    # renamed input's basename, and when there are multiple
    # input files it adds an index suffix (`_1`, `_2`, ...). For
    # sample_A (2 R1 lanes + 2 R2 lanes) we expect four
    # `sample_A_N_fastqc.html` files. For sample_C (single-end,
    # 1 file) we expect one `sample_C_fastqc.html`. So we glob
    # with `${s}_*_fastqc.html` and `${s}_fastqc.html`.
    local expected_samples=(sample_A sample_B sample_C sample_D)
    for s in "${expected_samples[@]}"; do
        local reports
        reports=$(find work -name "${s}_*_fastqc.html" -o -name "${s}_fastqc.html" 2>/dev/null)
        if [[ -z "$reports" ]]; then
            echo "FAIL: no ${s}_*_fastqc.html found in work/ — fastqc didn't run on ${s}" >&2
            return 1
        fi
        # Every report should be non-empty (real fastqc output
        # is at least a few KB even on a tiny input).
        while IFS= read -r report; do
            local report_size
            report_size=$(stat -f %z "$report" 2>/dev/null || stat -c %s "$report" 2>/dev/null)
            if [[ -z "$report_size" || "$report_size" -lt 1000 ]]; then
                echo "FAIL: $report is suspiciously small ($report_size B) — fastqc didn't run properly" >&2
                return 1
            fi
        done <<< "$reports"
    done
    # Confirm both lanes of sample_A made it into fastqc's
    # input list. The .command.sh is the most reliable place to
    # check — the script's `printf "%s %s\n" <old> <new>` line
    # lists every renamed file. Nextflow's workdirs are hash-
    # named, not process-named, so we search by content.
    local a_cmd
    a_cmd=$(grep -l 'sample_A_S1_L001_R1_001' work/*/*/.command.sh 2>/dev/null | head -1)
    if [[ -z "$a_cmd" ]]; then
        echo "WARN: could not find sample_A's fastqc .command.sh to verify multi-lane input"
    elif ! grep -q 'sample_A_S1_L002_R1_001' "$a_cmd" 2>/dev/null; then
        echo "FAIL: fastqc's input for sample_A doesn't include both R1 lanes:" >&2
        grep 'printf' "$a_cmd" | head -1 >&2
        return 1
    fi
    local report_count
    report_count=$(find work -name "${expected_samples[0]}_*_fastqc.html" -o -name "${expected_samples[0]}_fastqc.html" 2>/dev/null | wc -l | tr -d ' ')
    echo "==> POC smoke test passed: all 4 samples have fastqc HTML reports"
    echo "    sample_A produced $report_count reports (one per input file: 2 R1 lanes + 2 R2 lanes)"
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
    poc-smoke)
        # End-to-end smoke: real fastqc against the test fixtures.
        # No nf-test (so this is safe on runners without nf-test)
        # and no separate unit layer (the parser is now in the main
        # lib and tested by the main unit suite).
        run_poc_setup
        run_poc_smoke
        ;;
    poc-test)
        # The full POC stack: nf-test (stub) + end-to-end smoke.
        # Slow (needs nextflow + nf-test + the setup.sh clone)
        # but most thorough.
        run_poc_setup
        run_poc_nf_test
        run_poc_smoke
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
        # If the POC has been set up, also run the POC smoke
        # layer. We don't auto-run nf-test for the POC because
        # it's a new layer and the user opts in via --poc-test.
        if [[ -d examples/poc/lib && -d examples/poc/nf-core-modules ]]; then
            if command -v nextflow >/dev/null 2>&1; then
                run_poc_smoke
            fi
        else
            echo "(examples/poc not set up — skipping POC tests; run examples/poc/bin/setup.sh to enable)"
        fi
        ;;
esac