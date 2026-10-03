# Security Policy

## Supported Versions

Only the latest released version of `reshape-samplesheet` receives
security fixes. Older versions are not patched.

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | ✅                 |
| < 0.1.0 | ❌                 |

## Reporting a Vulnerability

**Please do not file a public issue.** Security vulnerabilities should
be reported privately so we have a chance to ship a fix before the
details are public.

Open a private report at:

> **https://github.com/inful/reshape-samplesheet/security/advisories/new**

If GitHub's advisory interface is unavailable for any reason, contact
the maintainer directly via GitHub (`@inful`) and we will follow up.

A good report includes:

- A short description of the vulnerability and its impact.
- The version(s) affected.
- Steps to reproduce, or a minimal samplesheet that triggers it.
- Any known mitigations or workarounds.

You should hear back within **5 business days**. We aim to confirm
the issue, develop a fix, and coordinate a disclosure timeline
together — typically within 30 days of confirmation for
non-trivial issues.

## Scope

This project is a pure-Groovy parser/reshaper that runs inside the
Nextflow JVM. The realistic attack surface is:

- **Path traversal via crafted samplesheet or fastq filenames**: the
  parser reads the samplesheet as text only and does not `eval` any
  content, so a malicious samplesheet can only trigger
  `IllegalArgumentException` (which is safe — no partial output is
  ever written).
- **Path-injected outputs**: `writeReshaped` writes to a user-supplied
  output directory using the samplesheet basename. If you call it
  with untrusted samplesheets against an attacker-controlled output
  path, treat the output directory as untrusted.
- **Memory / CPU**: the parser is O(samples × fastq_files) for the
  matching step. A samplesheet with hundreds of thousands of entries
  and a fastq directory with millions of files will take noticeable
  time and memory. There is no streaming parser; the full samplesheet
  is read into memory.

Anything outside this scope (e.g. Nextflow runtime, Java/Nextflow
plugin vulnerabilities) should be reported upstream to the Nextflow
project, not here.