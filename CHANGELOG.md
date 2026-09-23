# Changelog

This root changelog tracks portfolio/monorepo-level changes. Component-specific history remains inside each application directory.

## MVC2 r1 — Portfolio baseline

- combined Windows Hub, Android/SUNMI agent, and Chrome extension into one monorepo;
- introduced layered MVC2 boundaries and shared JSON contracts;
- preserved reliability-sensitive production-derived runtime behavior during structural migration;
- added regression-oriented CI/build workflows;
- added public-repository hardening: secret/data exclusions, secure signing guidance, security policy, contribution workflow, architecture/case-study documentation;
- removed committed Android signing material from the public edition.

### Component baselines

- Hub: `2.2.5`
- ShopeeFood Android agent: `2.0.10`
- GrabFood Chrome extension: `2.0.1`
