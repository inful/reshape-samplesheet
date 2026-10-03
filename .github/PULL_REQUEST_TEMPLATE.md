## Summary

<!-- One or two sentences. What does this PR do, and why? -->

## Related issue

<!-- Link the issue this closes, e.g. "Closes #123". Write "n/a" if there is no issue. -->

## Type of change

- [ ] Bug fix (non-breaking change that fixes an issue)
- [ ] New feature (non-breaking change that adds functionality)
- [ ] Breaking change (fix or feature that would change existing behaviour; warrants a MAJOR version bump)
- [ ] Documentation update
- [ ] Internal refactor / no user-facing change

## How was this tested?

- [ ] `bin/test.sh --full` — all 79 unit tests pass
- [ ] `bin/test.sh --smoke` — Nextflow end-to-end passes
- [ ] New tests added for new behaviour (preferred)
- [ ] Regression test added for any bug fix (preferred)

## CHANGELOG

- [ ] Updated `CHANGELOG.md` under `[Unreleased]` with a one-line entry under the appropriate section (`Added` / `Changed` / `Fixed` / `Removed`)

## API impact

If this changes the public API surface (anything in `lib/SamplesheetReshape.groovy` that callers can `include`/`import`), describe the impact here. See CONTRIBUTING.md → "Public API and breaking changes" for the policy.