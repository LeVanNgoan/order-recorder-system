#!/usr/bin/env python3
"""Fail CI when common private/runtime artifacts are accidentally committed."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]

BLOCKED_SUFFIXES = {
    '.jks', '.keystore', '.p12', '.pfx', '.pem', '.key',
    '.db', '.sqlite', '.sqlite3', '.apk', '.aab', '.exe', '.msi', '.crx',
}
BLOCKED_NAMES = {
    'hub_config.json', 'orders.json', '.env', 'local.properties', 'secrets.properties'
}
GENERATED_PARTS = {'__pycache__', '.gradle', 'node_modules'}
BLOCKED_RUNTIME_PARTS = {'backups', 'exports', 'logs'}

# These are intentionally conservative patterns for public-source hygiene, not a full secret scanner.
SECRET_PATTERNS = [
    re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    re.compile(r'github_pat_[A-Za-z0-9_]{20,}'),
    re.compile(r'ghp_[A-Za-z0-9]{20,}'),
    re.compile(r'AIza[0-9A-Za-z_-]{20,}'),
    re.compile(r'(?i)(?:client_secret|private_key)\s*[:=]\s*["\'][^"\']{8,}["\']'),
]

ALLOW_TEXT_SUFFIXES = {
    '.py', '.java', '.js', '.json', '.md', '.yml', '.yaml', '.gradle', '.properties',
    '.html', '.css', '.bat', '.txt', '.xml', '.cff', '.toml', '.sh'
}

problems=[]
for p in ROOT.rglob('*'):
    if not p.is_file():
        continue
    rel=p.relative_to(ROOT)
    if '.git' in rel.parts:
        continue
    # Generated caches are ignored by .gitignore and may legitimately exist after local QA.
    if any(x in rel.parts for x in GENERATED_PARTS):
        continue
    if p.name in BLOCKED_NAMES or p.suffix.lower() in BLOCKED_SUFFIXES or any(x in rel.parts for x in BLOCKED_RUNTIME_PARTS):
        problems.append(f'blocked public artifact: {rel}')
        continue
    if p.suffix.lower() not in ALLOW_TEXT_SUFFIXES:
        continue
    try:
        text=p.read_text(encoding='utf-8', errors='ignore')
    except Exception:
        continue
    for pattern in SECRET_PATTERNS:
        if pattern.search(text):
            problems.append(f'possible secret pattern: {rel} / {pattern.pattern}')

if problems:
    print('PUBLIC_REPO_CHECK_FAIL')
    for x in problems:
        print(' -', x)
    sys.exit(1)

print('PUBLIC_REPO_CHECK_PASS')
