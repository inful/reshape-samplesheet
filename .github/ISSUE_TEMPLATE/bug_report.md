---
name: Bug report
about: Report incorrect behaviour or a failure
title: "[bug] "
labels: bug
assignees: ""
---

## What you did

The exact command or Groovy call. A minimal reproducer is best:

```bash
nextflow run main.nf --samplesheet ... --fastq_dir ... --outdir ... --recursive ...
```

## What you expected to happen

## What actually happened

Include the **complete** error message and stack trace, verbatim.

## Samplesheet excerpt

Just the header and the affected rows (redact PHI / patient identifiers).
If the bug is reproducible with one of the fixtures under `tests/data/`,
say so — no need to paste samplesheet content in that case.

## Nextflow version

Output of `nextflow -version`:

```
<!-- paste here -->
```

## Operating system and Java version

## Additional context

Anything else that might help diagnose — input sizes (number of samples,
fastq directory size), runtime environment, related issues, etc.