---
name: Feature request
about: Suggest a new capability or change
title: "[FE request] "
labels: enhancement
assignees: ""
---

## Use case

What are you trying to accomplish? Describe the biological/scientific
motivation first — the use case drives the API design, not the other
way around.

## Proposed API

How would the API look? Be specific — show example code:

```groovy
// What you imagine calling
def csv = SamplesheetReshape.reshape(samplesheet, fastqDir, [
    // new option here
])
```

## Alternatives considered

What other ways have you thought about solving this? Why is this
proposal better than the alternatives?

## Breaking change?

- [ ] This change is **non-breaking** (new opt, new method, internal refactor)
- [ ] This change **breaks** existing callers in a documented fashion (would warrant a MAJOR bump)