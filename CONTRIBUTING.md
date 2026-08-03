# Contributing

## Scope and safety

Changes must preserve the authenticated TLS control protocol, command allowlist, APK-first control backend, opt-in ADB policy, `scrcpy control=false`, and MediaProjection/DRM boundaries. Do not add reflection, arbitrary command execution, hidden capture, credential logging, or unverified device-support claims.

Use placeholders such as `<token>`, `<internal_ip>`, `<server_path>`, and `<email>` in examples. Never commit `.env`, keys, tokens, cookies, databases, logs, captures, signing material, or real infrastructure details.

## Development

Keep changes small and follow the existing Kotlin, Zig, Swift, build, and naming conventions. Every defect fix needs a directly related regression test. Hardware-only behavior must remain `UNVERIFIED` until evidence records the device model, firmware, system version, architecture, and sanitized result.

Run the relevant gate first; before proposing a complete change, run:

```text
./scripts/run-quality-gates.sh
```

Android release signing is external to the repository. A successful unsigned or debug build does not verify formal signing or device behavior.

## Pull requests

Describe the problem, compatibility impact, validation evidence, and any remaining `UNVERIFIED` items. Do not mix unrelated refactors. Security-sensitive findings must use private vulnerability reporting instead of a public pull request.
