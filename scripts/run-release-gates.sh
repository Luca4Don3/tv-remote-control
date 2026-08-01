#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${TVRC_SECURITY_AUDIT_SCANNER:?TVRC_SECURITY_AUDIT_SCANNER must point to audit_git_privacy.py}"

case "$TVRC_SECURITY_AUDIT_SCANNER" in
    /*) ;;
    *) echo "TVRC_SECURITY_AUDIT_SCANNER must be an absolute path" >&2; exit 1 ;;
esac
[ -f "$TVRC_SECURITY_AUDIT_SCANNER" ] || {
    echo "security audit scanner not found: $TVRC_SECURITY_AUDIT_SCANNER" >&2
    exit 1
}

audit_report="$project_root/.temp/release-audit.json"
mkdir -p "$project_root/.temp"
set +e
python3 "$TVRC_SECURITY_AUDIT_SCANNER" --repo "$project_root" --format json > "$audit_report"
status=$?
set -e
if [ "$status" -ne 0 ]; then
    echo "offline security audit did not pass (exit $status); formal packaging is blocked" >&2
    exit "$status"
fi
