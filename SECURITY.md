# Security Policy

## Public-repository rule

This repository must never contain real merchant/customer data or production credentials.

Do not commit:

- Hub API keys or runtime `hub_config.json` files;
- SQLite databases, backups, order exports, or technical logs from real devices;
- Android signing keystores or passwords;
- private LAN/Tailscale operational details that are not generic examples;
- screenshots containing real phone numbers or identifiable customer information.

The included `.gitignore` blocks the common forms of these files, but contributors are still responsible for reviewing staged changes before every push.

## Runtime security model

The Hub currently uses a local runtime-generated API key for agent write endpoints. Dashboard/management access is restricted to the Hub PC and the configured remote-network boundary. Destructive management actions require an admin password where implemented.

This is an operational tool, not an Internet-facing SaaS service. Do not expose the Hub HTTP listener directly to the public Internet.

## Android signing

The public repository deliberately excludes signing material. Use local secure storage or GitHub Actions Secrets/Environments for release signing.

## Reporting a vulnerability

If you discover a security issue, do not publish customer data, secrets, or exploit details in a public issue. Contact the repository owner privately first, then create a sanitized issue after the sensitive details have been removed.
