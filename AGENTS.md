# MuonSSH Agent Instructions

- When the user says "запусти" or "run it", start the app and leave it running for manual testing. Do not use `timeout`, do not auto-stop it, and do not treat "run" as a short smoke test unless the user explicitly asks for a temporary run.
- Run MuonSSH test builds with the persistent test profile, preserving saved sessions/settings between runs:
  `/home/userk/muonssh-test-profile`
- Do not copy or use the real user profile at `/home/userk/muon-ssh` unless the user explicitly asks for it.
- Launch test builds with:
  `-Duser.home=/home/userk/muonssh-test-profile`
  `-Djava.util.prefs.userRoot=/home/userk/muonssh-test-profile/.java-prefs`
- Canonical test launch command: `./scripts/run-muonssh-test.sh`. It rebuilds the jar, writes/updates the user systemd unit, restarts `muonssh-test.service`, and leaves the GUI running.
- Keep the GUI process alive through the user systemd unit when the user wants to test manually.
