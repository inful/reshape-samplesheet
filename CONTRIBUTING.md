# Contributing to `reshape-samplesheet`

Thanks for your interest in contributing! This document covers the basics;
the README documents the public API and error contract, which take
precedence over anything else here if they disagree.

## Filing issues

Before opening an issue, please:

1. **Search existing issues** to make sure yours hasn't been reported.
2. **Reproduce on the latest `main`** if possible. Issues on older
   versions get closed faster than they get fixed.
3. **Include the inputs**: the samplesheet (or a redacted version that
   still reproduces the problem), the `fastq_dir` layout (filenames only
   are fine), the exact `nextflow run` / Groovy invocation, and the
   error message verbatim.

### Bug reports

A good bug report includes:

- **What you did** (the exact command or Groovy call).
- **What you expected to happen**.
- **What actually happened**, including the full error message and
  stack trace.
- **Samplesheet excerpt** (just the header and the affected rows;
  redact PHI / patient identifiers).
- **Nextflow version** (`nextflow -version`).

### Feature requests

Open an issue with `[feature]` in the title. Describe the use case
first, then the proposed API — that order helps reviewers see whether
the API matches the need.

## Filing pull requests

1. **Fork** the repo and **create a branch** off `main`.
   Branch names: `fix/<short-description>`, `feat/<short-description>`,
   or `docs/<short-description>`.
2. **Keep PRs focused**. One logical change per PR.
3. **Add or update tests**. New behaviour needs new tests. A bug fix
   should ideally add a regression test that fails on `main` and passes
   on your branch.
4. **Run the full suite** locally before pushing:
   ```bash
   bin/test.sh --full
   ```
   All 79 tests must pass. PRs that break tests will not be merged
   without a follow-up.
5. **Run the smoke test** if you have Nextflow installed:
   ```bash
   bin/test.sh --smoke
   ```
   This exercises the Nextflow → `lib/` auto-load path, which the unit
   tests don't cover.
6. **Update `CHANGELOG.md`** under the `[Unreleased]` section with a
   one-line entry under `Added` / `Changed` / `Fixed` / `Removed`.
   Keep it brief — the PR description can elaborate.
7. **Update `README.md`** if you change the public API, add an option,
   or fix a behaviour the README documented incorrectly.

## Local development setup

The `mise.toml` at the repo root pins the dev tool versions
(`java@21`, `groovy@latest`). With [mise](https://mise.jdx.dev/)
on your machine, just `cd` into the repo and the tools become
available automatically:

```bash
# Optional — installs the pinned tools
mise use --yes

# Then run the suite
bin/test.sh --full
```

If you prefer SDKMAN or Homebrew instead:

```bash
# SDKMAN
sdk install java 21.0.2-tem
sdk install groovy

# Homebrew
brew install java groovy
```

You also need [Nextflow](https://www.nextflow.io/) installed for the
smoke test. Nextflow is **not** required for the unit tests.

## Code style

- **Groovy classes** (`lib/*.groovy`): match the existing style — 4-space
  indent, single-quote strings, Javadoc on every public method with
  `@param` / `@return` / `@throws` tags. The public facade is
  `SamplesheetReshape`; everything else in `lib/` is internal.
- **Nextflow files** (`main.nf`, `subworkflows/**/*.nf`): match the
  existing style — 4-space indent, `// comment` headers explaining the
  workflow, take/main/emit blocks, single-line comments on each emit.
- **Tests** (`tests/test_*.groovy`): one `runner.test('…', { … })` per
  assertion or closely-related assertion group, with a one-line
  description that reads as a sentence. Use `File.createTempFile` and
  `Files.createTempDirectory` for fixtures (see `test_reshaper.groovy`
  for the pattern); don't commit generated fixture files.
- **Error messages**: every `IllegalArgumentException` must include
  the file path. Format prefix: `"<what failed>: <details>"`. Multiple
  problems are joined with `\n  ` and reported in one exception.

## Public API and breaking changes

The public API surface is the set of `static` methods on
`SamplesheetReshape`. The error contract (which exceptions are thrown
for which inputs) is part of the API and **must not change silently**.

- **Adding new methods**: free.
- **Adding new `opts` keys**: free — unknown options are silently
  ignored, so callers won't break.
- **Changing how a known option behaves**: **breaking**. Goes in a
  `MAJOR` bump with a `### Changed (BREAKING)` line in CHANGELOG.
- **Removing a method or tightening the error contract**: **breaking**.

## Release process

Releases are tagged from `main` following [Semantic Versioning].
Tags use the bare version (`0.1.0`, no `v` prefix) to match nf-core
convention. The CHANGELOG entry for the release is added at tag time
and the version in `nextflow.config` and `CITATION.cff` is bumped in
the same commit.

## License

By contributing, you agree that your contributions will be licensed
under the MIT License (see [LICENSE](LICENSE)).