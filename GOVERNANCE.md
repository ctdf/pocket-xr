# Governance

Pocket XR is open source under the GPLv3: anyone may use, study, change and share it, and anyone may fork it. This page
says how **this** repository is run.

## Lead maintainer

**Mike Haye** (connect@mikehaye.com) started the project and is its lead maintainer. The lead maintainer:
- sets the project's direction and has the final say on what is merged;
- owns the repository, the GitHub organisation, the release signing key and the "Pocket XR" name (see TRADEMARK.md);
- signs and publishes the official releases.

## How changes get in

- **Every change to `main` goes through a pull request,** the lead maintainer's own changes included.
- **A PR merges when:**
  - a code owner approves it (CODEOWNERS);
  - CI passes: the build, the unit tests and the clean-source check;
  - it follows CONTRIBUTING.md (the DCO sign-off included).
- **Disagreements** are discussed openly on the issue or PR. If it can't be settled, the lead maintainer decides and
  says why.

## Becoming a maintainer

Maintainers review and merge PRs in the areas they know, alongside the lead maintainer. Someone becomes a maintainer
when they have:
- contributed good PRs and reviews over a few months;
- shown care for the project's quality and its community;
- been invited by the lead maintainer, and accepted.

Maintainers are added to CODEOWNERS for their areas. A maintainer who steps back, or who doesn't follow the Code of
Conduct, may be removed from the role by the lead maintainer.

## If the project stops

If the lead maintainer can no longer run the project, maintainers may carry it on in this repository with his
agreement, or in a fork under another name. The licence guarantees the code stays free either way.

## Changes to this document

Changes to governance are made by pull request, approved by the lead maintainer.
