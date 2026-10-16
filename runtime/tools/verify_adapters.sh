#!/bin/sh
# Compile, statically check and boot-audit Fabric adapters.
#   tools/verify_adapters.sh [--no-audit] v1_21_8 legacy/v1_8_9 ...   (default: every adapter in both builds)
# Boot audits open a game window for ~30 s each (runAudit: title screen, force-load every mixin target, quit).
cd "$(dirname "$0")/.." || exit 1
audit=1
[ "$1" = "--no-audit" ] && { audit=0; shift; }
adapters="$*"
[ -z "$adapters" ] && adapters="$(grep -o '"v[0-9_]*"' settings.gradle.kts | tr -d '"') $(grep -o '"v[0-9_]*"' legacy/settings.gradle.kts | tr -d '"' | sed 's#^#legacy/#')"
gradle=./gradlew
[ -f gradlew.bat ] && case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) gradle=./gradlew.bat ;; esac
fail=0
for a in $adapters; do
    printf '%-16s ' "$a"
    # legacy/vX lives in the Legacy Fabric build (runtime/legacy).
    case "$a" in legacy/*) build="-p legacy"; task="${a#legacy/}" ;; *) build=""; task="$a" ;; esac
    log=build/verify-$(echo "$a" | tr / _)
    if ! $gradle $build ":$task:build" -q --console=plain > "$log-build.log" 2>&1; then
        echo "BUILD FAILED ($log-build.log)"; fail=1; continue
    fi
    if ! python tools/mixin_check.py "$a" > "$log-check.log" 2>&1; then
        echo "MIXIN CHECK FAILED"; cat "$log-check.log"; fail=1; continue
    fi
    if [ $audit = 1 ]; then
        $gradle $build ":$task:runAudit" -q --console=plain > "$log-audit.log" 2>&1
        if grep -aq "\[kite-audit\] finished" "$log-audit.log" && ! grep -aq "Mixin apply.*failed\|InvalidInjection\|kite-audit\] FAILED" "$log-audit.log"; then
            echo "ok (built, checked, booted)"
        else
            echo "AUDIT FAILED ($log-audit.log)"; grep -a "Mixin apply.*failed\|InvalidInjection\|FAILED" "$log-audit.log" | head -3 | cut -c1-300; fail=1
        fi
    else
        echo "ok (built, checked)"
    fi
done
exit $fail
