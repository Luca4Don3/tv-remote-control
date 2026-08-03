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
case "$status" in
    0)
        echo "offline security audit passed (exit 0)"
        ;;
    2)
        # REVIEW_REQUIRED：扫描器对无法完整解码的内容（如 gradle-wrapper.jar 等
        # 已核实的官方二进制）采用 fail-closed 策略。仅当发布者人工审查报告并
        # 通过 TVRC_RELEASE_ACCEPT_REVIEW=<reason> 显式确认后才放行；
        # BLOCKED(1)/ERROR(3) 仍一律拦截。
        : "${TVRC_RELEASE_ACCEPT_REVIEW:?security audit is REVIEW_REQUIRED (exit 2); set TVRC_RELEASE_ACCEPT_REVIEW=<reason> after reviewing $audit_report}"
        blocked=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["summary"]["blocked"])' "$audit_report")
        [ "$blocked" -eq 0 ] || {
            echo "audit report contains BLOCKED findings; cannot accept as REVIEW_REQUIRED" >&2
            exit 1
        }
        echo "security audit REVIEW_REQUIRED accepted: $TVRC_RELEASE_ACCEPT_REVIEW"
        ;;
    *)
        echo "offline security audit did not pass (exit $status); formal packaging is blocked" >&2
        exit "$status"
        ;;
esac
