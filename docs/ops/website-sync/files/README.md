# Semper — Digital Image Correlation

**Product site (v1):** [semperdic.github.io/website](https://semperdic.github.io/website/)

This repository is the public home for:

- The product website source (Astro → **GitHub Pages**)
- Customer **Discussions** (Q&A, Ideas, Announcements, General)
- Customer **Issues** (bugs and feature requests)

The Android app and correlation engine live in a separate private repository.
A snapshot of the app operating manual, workflows, legal drafts, and images used
for content sync lives in [`docs/source/`](docs/source/) so public contributors
can review Manual copy without private-repo access. See
[`docs/CONTENT-SYNC.md`](docs/CONTENT-SYNC.md).

A custom domain (`semperdic.com`) can be added later when traffic justifies it — see [docs/HOSTING.md](docs/HOSTING.md).

## Links

| | |
|---|---|
| Site (apex redirects here) | https://semperdic.github.io/ → https://semperdic.github.io/website/ |
| Download / Play | https://semperdic.github.io/website/download/ |
| Manual | https://semperdic.github.io/website/manual/ |
| Community | https://semperdic.github.io/website/community/ |
| Privacy | https://semperdic.github.io/website/privacy/ |
| Report a bug | [New bug report](https://github.com/semperdic/website/issues/new?template=bug_report.yml) |
| Request a feature | [New feature request](https://github.com/semperdic/website/issues/new?template=feature_request.yml) |
| Ask a question | [Discussions → Q&A](https://github.com/semperdic/website/discussions/new?category=q-a) |

### Short paths (redirect into `/website`)

| Path | Lands on |
|---|---|
| `/website/auth/` | Privacy · Authentication |
| `/website/data/` | Privacy · Data storage |
| `/website/data-policy/` | Privacy · Data policy |
| `/website/privacy-policy/` | Privacy · Privacy policy |
| `/website/legal/` | Privacy |
| `/website/help/` | Support |
| `/website/docs/` | Manual |
| `/website/faq/` | Manual · FAQ |
| `/website/troubleshooting/` | Support · Troubleshooting |

## Code of conduct

Participation is governed by our [Code of Conduct](CODE_OF_CONDUCT.md).

## Develop the site

```bash
npm install
npm run dev
```

See [RELEASING.md](RELEASING.md) for publishing APKs and [docs/HOSTING.md](docs/HOSTING.md) for Pages deploy.
