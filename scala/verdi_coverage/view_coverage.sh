#!/bin/bash
# Open a coverage database in the Verdi coverage GUI (needs X11/DISPLAY).
#
#   ./view_coverage.sh              # global merged.vdb (trend number only — the
#                                   # cross-design merge drops non-matching modules)
#   ./view_coverage.sh <name>       # ACCURATE per-group DB. <name> may be a prefix:
#                                   #   ./view_coverage.sh SidebandSwitchTest
#                                   # opens the group directly if the prefix matches
#                                   # exactly one, otherwise lists the candidates.
#
# Extra args after the name are passed through to verdi.
cd "$(dirname "$0")"

if [ $# -ge 1 ] && [ "${1#-}" = "$1" ]; then
  NAME="$1"
  shift
  DB="suites/$NAME.vdb"
  if [ ! -d "$DB" ]; then
    # prefix match: suites are split per design group with long generated names
    MATCHES=$(ls -d "suites/$NAME"*.vdb 2>/dev/null)
    COUNT=$(echo "$MATCHES" | grep -c . )
    if [ "$COUNT" -eq 1 ] && [ -n "$MATCHES" ]; then
      DB="$MATCHES"
      echo "Opening: $(basename "$DB" .vdb)"
    elif [ "$COUNT" -gt 1 ]; then
      echo "'$NAME' matches $COUNT groups — pick one:"
      echo "$MATCHES" | sed 's|suites/||; s|\.vdb$||; s|^|  |'
      exit 1
    else
      echo "ERROR: no suite matches '$NAME'. Available:"
      ls suites 2>/dev/null | sed 's/\.vdb$//; s/^/  /' || echo "  (none — run ./run_verdi_coverage.sh first)"
      exit 1
    fi
  fi
else
  DB="merged.vdb"
  if [ ! -d "$DB" ]; then
    echo "ERROR: merged.vdb not found — run ./run_verdi_coverage.sh first"
    exit 1
  fi
fi

exec verdi -cov -covdir "$DB" "$@"
