# VPS Ledger: concept and implementation plan

## Summary

VPS Ledger is a VPS inventory and operational reminder layer built on top of the current Muon SSH desktop client. The first release keeps the existing SSH, SFTP, jump host and terminal behavior, but replaces the session persistence backend with SQLite and extends each host record with VPS ownership, billing and sync metadata.

The project remains a GPLv3 derivative of Muon SSH. Upstream `devlinx9/muon-ssh` and this repository both use GPL-3.0, so redistributed builds must keep the license, notices and corresponding source availability.

## First release

- Use `vps-ledger.db` in the application config directory as the primary state store.
- Import the existing `session-store.json` once, keep it as `session-store.json.backup-before-sqlite`, and preserve the old JSON reader for imports.
- Keep `SessionInfo` as the SSH compatibility object and add VPS metadata directly to it for the first release.
- Rename the manager UI to `Server manager` and add a `Description` tab with provider selection, billing metadata, status, tags, notes, external references and sync flags.
- Put the `Providers` directory on the main sidebar next to `New connection`, not inside the server manager dialog.
- Move provider data into a dedicated provider directory with website, panel URL, billing URL, account ID, notes and tags.
- Add a VPS overview dialog with filters for provider, status, tags and upcoming payment dates.

## Integrations

Vikunja stores one task per VPS billing action. Fixed-period billing uses `next_payment_date`; hourly-balance billing uses `next_balance_check_date` and creates a balance-check task instead of estimating depletion. The local SQLite row stores `vikunja_task_id`; saving a host schedules an asynchronous create or update through the Vikunja API. The task uses `due_date` at 09:00 in the local time zone and a reminder relative to that due date.

Infisical is the first cloud state backend. Local SQLite remains the offline cache. Host metadata is synchronized as JSON and SSH keys are separate secrets:

- `/vps-manager/index/HOSTS_JSON`
- `/vps-manager/providers/PROVIDERS_JSON`
- `/vps-manager/hosts/{hostId}/HOST_JSON`
- `/vps-manager/hosts/{hostId}/SSH_PASSWORD`
- `/vps-manager/hosts/{hostId}/SSH_PRIVATE_KEY`
- `/vps-manager/hosts/{hostId}/SSH_PUBLIC_KEY`

SSH passwords are stored as separate secrets, not inside `HOST_JSON`. Private key sync is disabled by default and must be explicitly enabled in settings and per host.

## Rollout

1. Add SQLite storage and migration from the legacy JSON file.
2. Extend host UI and persistence.
3. Add Vikunja settings and best-effort background payment task sync.
4. Add Infisical settings and manual sync.
5. Rename branding, package metadata and config directory in a later release.

## Test plan

- Unit-test JSON-to-SQLite migration, repository CRUD, Vikunja payloads and Infisical paths.
- Mock HTTP for Vikunja and Infisical clients.
- Manually verify import, edit, connect, payment task sync and restore on a fresh config directory.
