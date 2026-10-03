#!/usr/bin/env bash
# Generates fake but realistic fastq.gz fixtures for tests.
# BCL-conversion style: {SampleID}_S{N}_L00{Lane}_R{Read}_001.fastq.gz
set -euo pipefail

OUT="${1:-tests/fastqs}"
mkdir -p "$OUT"

# Generate a minimal valid fastq.gz with one record
mk_fastq() {
    local name="$1"
    printf '@read1\nACGTACGT\n+\nIIIIIIII\n' | gzip -n > "${OUT}/${name}"
}

# sample_A: paired, two lanes
mk_fastq "sample_A_S1_L001_R1_001.fastq.gz"
mk_fastq "sample_A_S1_L001_R2_001.fastq.gz"
mk_fastq "sample_A_S1_L002_R1_001.fastq.gz"
mk_fastq "sample_A_S1_L002_R2_001.fastq.gz"

# sample_B: paired, one lane
mk_fastq "sample_B_S2_L001_R1_001.fastq.gz"
mk_fastq "sample_B_S2_L001_R2_001.fastq.gz"

# sample_C: single, one lane (no R2)
mk_fastq "sample_C_S3_L001_R1_001.fastq.gz"

# sample_D: paired, one lane
mk_fastq "sample_D_S4_L001_R1_001.fastq.gz"
mk_fastq "sample_D_S4_L001_R2_001.fastq.gz"

# Unrelated file that should not match any sample
mk_fastq "unrelated_S99_L001_R1_001.fastq.gz"

echo "Generated fixtures in ${OUT}"
ls -1 "$OUT"
