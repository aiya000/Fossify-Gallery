#!/usr/bin/env bash
# Every driving script, one after another, into one run directory.
#
# Each script seeds the app itself, so they do not depend on each other and any one of them can be
# run on its own while a change is being worked on.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export RUN_DIR="${RUN_DIR:-$(dirname "$here")/runs/$(date +%Y-%m-%d-%H%M%S)}"
mkdir -p "$RUN_DIR"

failed=0
for script in "$here"/[0-9][0-9]-*.sh; do
    echo
    echo "#################### $(basename "$script")"
    if ! bash "$script"; then
        failed=$((failed + 1))
    fi
done

echo
if [ "$failed" -gt 0 ]; then
    echo "$failed script(s) failed. Everything from this run is in $RUN_DIR"
    exit 1
fi

echo "every script passed. Everything from this run is in $RUN_DIR"
