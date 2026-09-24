# Security policy

Haven is an offline vault: it has no server, no account and no network permission, so every
security issue is a local one (on-device storage, key handling, alarm delivery, backups, the
duress feature) and is fixed in the app itself.

## Reporting a vulnerability

Please do **not** open a public issue for a security problem. Use GitHub's private
**Report a vulnerability** form under this repository's *Security* tab. Include:

- the Haven version (Settings → About) and Android version;
- what an attacker needs (physical access, an unlocked phone, root, a malicious app, a crafted
  backup file, …);
- steps to reproduce, and what data or control the issue exposes.

You will get an acknowledgement within a few days. Please give us a reasonable time to ship a fix
before publishing details; we will credit you in the release notes unless you prefer not to be named.

## Scope

In scope: anything in this repository that weakens the guarantees described in the README's
"Security at a glance" section or in `backup-format/FORMAT.md`.

Out of scope, by design and documented to users in the app: remnants in flash storage after
deletion, clipboard history kept by other apps or keyboards, attacks that require a compromised
Android OS or a modified Haven build, and the fact that an alarm cannot ring while the phone is off.

## Supported versions

Only the latest release is supported. There is no automatic update channel; users install new
versions themselves.
