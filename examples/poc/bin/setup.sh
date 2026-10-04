#!/usr/bin/env bash
# One-time setup for the POC at examples/poc/.
#
# Two things are staged (both are gitignored, so the setup is local):
#   1. `lib/` — the repo-root Groovy library is copied here. The POC's
#      projectDir is examples/poc/ (not the repo root), so Nextflow
#      only auto-loads files from examples/poc/lib/. Copying (rather
#      than fighting Nextflow's classpath heuristics or symlinking)
#      keeps the POC self-contained and reproducible.
#
#   2. `nf-core-modules/` — a sparse-friendly clone of
#      https://github.com/nf-core/modules pinned to a specific commit
#      SHA. The include directive in main.nf points into this clone,
#      so the version of CAT_FASTQ we exercise is fully reproducible.
#      Bump NFCORE_MODULES_SHA when you want to pick up upstream
#      changes to the cat module.
#
# Re-running this script is safe: both steps are skipped if the
# destination already exists.
#
# Usage:
#   examples/poc/bin/setup.sh

set -euo pipefail

# Resolve to the directory containing this script, regardless of how
# the script is invoked (relative path, absolute path, or symlink).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
POC_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${POC_DIR}/../.." && pwd)"

# Pinned to a known-good commit. The most recent commit on
# nf-core/modules that updated the cat/fastq main.nf at the time
# this POC was written. Bump with care — the include path is hard-
# coded in examples/poc/main.nf, and a different SHA will only affect
# us if upstream renames or restructures the module.
NFCORE_MODULES_SHA="6d46786420b4d7bc88eba026eb389c0c5535d120"

# 1) Stage the repo-root lib/ into the POC's projectDir so Nextflow's
# auto-classpath picks up SamplesheetReshape and friends.
if [[ ! -d "${POC_DIR}/lib" ]]; then
    echo "==> Copying ${REPO_ROOT}/lib → ${POC_DIR}/lib"
    cp -R "${REPO_ROOT}/lib" "${POC_DIR}/lib"
else
    echo "==> lib/ already staged, skipping copy"
fi

# 2) Clone nf-core/modules at the pinned SHA. The full clone is fine
# here — nf-core/modules is a few hundred MB and a sparse clone
# (--filter=blob:none --sparse) is more complex than this POC needs.
# If the dir already exists, do not touch it; the user can `rm -rf
# nf-core-modules` to force a re-clone.
if [[ ! -d "${POC_DIR}/nf-core-modules" ]]; then
    echo "==> Cloning nf-core/modules@${NFCORE_MODULES_SHA}"
    git clone https://github.com/nf-core/modules.git "${POC_DIR}/nf-core-modules"
    (cd "${POC_DIR}/nf-core-modules" && git checkout --quiet "${NFCORE_MODULES_SHA}")
else
    echo "==> nf-core-modules/ already cloned, skipping (delete the dir to re-clone)"
fi

echo "==> POC setup complete"
echo ""
echo "Next steps:"
echo "  nextflow run examples/poc/main.nf \\"
echo "      --samplesheet tests/data/illumina_bcl2fastq.csv \\"
echo "      --fastq_dir   tests/fastqs \\"
echo "      --outdir      results-poc \\"
echo "      --recursive    false"
