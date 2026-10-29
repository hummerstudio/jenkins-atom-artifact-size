#!/usr/bin/env bash
# Sizes are exact: 4 KiB + 8 KiB of artifacts, a 6 KiB budget, so the gate has to fail.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

dir="$(mktemp -d)"; mkdir -p "$dir/dist"
dd if=/dev/zero of="$dir/dist/app.jar" bs=1024 count=4 2>/dev/null
dd if=/dev/zero of="$dir/dist/lib.jar" bs=1024 count=8 2>/dev/null
printf 'not a jar\n' >"$dir/dist/notes.txt"
printf '{"pattern": "dist/*.jar", "maxMb": "0", "topN": "10"}' >"$dir/input.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
[ "$(output "$dir" files)" = "2" ] || fail "file count: $(cat "$dir/output.json")"
[ "$(output "$dir" totalBytes)" = "12288" ] || fail "total bytes: $(cat "$dir/output.json")"
[ "$(output "$dir" largest)" = "dist/lib.jar" ] || fail "largest: $(cat "$dir/output.json")"
[ -f "$dir/out/artifact-size/index.html" ] || fail "no report"
grep -q "lib.jar" "$dir/out/artifact-size/index.html" || fail "report does not list the file"

# a budget trips the atom, report still written
dir="$(mktemp -d)"; mkdir -p "$dir/dist"
dd if=/dev/zero of="$dir/dist/big.jar" bs=1024 count=2048 2>/dev/null
printf '{"pattern": "dist/*.jar", "maxMb": "1"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "2 MB over a 1 MB budget must fail"
grep -q "over the 1.00 MB budget" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"
[ -f "$dir/out/artifact-size/index.html" ] || fail "report must exist on failure"

# nothing matched is a clear error
dir="$(mktemp -d)"
printf '{"pattern": "dist/*.jar"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "no artifacts must fail"
grep -q "nothing matched" "$dir/output.json" || fail "message not readable"

echo "smoke ok: totals, largest, budget gate, nothing matched"
