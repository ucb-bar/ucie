#!/bin/bash
# Per-area (subsystem) verification map: for every Chisel Module in each RTL
# area (src/uciedigital/<area>, src/tilelink, src/phy), show:
#   BEST   - best single-suite SCORE (all enabled metrics averaged)
#   LINE-U - LINE coverage UNION across all suites that exercise the module,
#            computed line-by-line from the suite reports. A module partially
#            covered by several different suites is credited for every line
#            any of them reached. Marked with '*' when the suites elaborated
#            the module with different parameters (line spaces don't align) —
#            then the value falls back to the best single suite's line %.
#   TESTED-BY - every suite whose design contains the module
# Modules that appear in no report are listed as NOT TESTED — coverage tools
# can't see code that no test elaborates.
#
# Reads reports/<Suite>/modlist.txt + modinfo.txt produced by
# run_verdi_coverage.sh; writes area_summary.txt here (and echoes it).
#
# Cross-suite scores are NOT merged with urg (cross-design urg merges are
# unreliable — see run_verdi_coverage.sh header); the union is computed at
# the source-line level instead, and only for the LINE metric.

set -u
COV_DIR="$(cd "$(dirname "$0")" && pwd)"
[ -n "$COV_DIR" ] || { echo "ERROR: cannot resolve script directory"; exit 1; }
SCALA_DIR="$(dirname "$COV_DIR")"

if ! ls "$COV_DIR"/reports/*/modlist.txt >/dev/null 2>&1; then
  echo "ERROR: no reports found — run ./run_verdi_coverage.sh first"
  exit 1
fi

# --- Line-level union across suites, parsed from modinfo.txt -----------------
UNION_MAP="$COV_DIR/.line_union.tmp"
python3 - "$COV_DIR" > "$UNION_MAP" <<'PYEOF'
import glob, os, re, sys

cov_dir = sys.argv[1]
line_re = re.compile(r"^\s*(\d+)\s+(\d+)/(\d+)\b")
head_re = re.compile(r"^(\w+) Coverage for Module : (\S+)\s*$")
# block-summary rows, e.g. "ALWAYS  276  402  368" / "INITIAL  1527  76  76"
block_re = re.compile(r"^(ALWAYS|INITIAL|ROUTINE|FINAL)\s+(\d+)\s+\d+\s+\d+")

# data[module][suite] = {lineno: (covered, total)}
data = {}
for path in glob.glob(os.path.join(cov_dir, "reports", "*", "modinfo.txt")):
    suite = os.path.basename(os.path.dirname(path))
    module = None
    blocks = []   # [(startline, type)] of the current module's Line section
    with open(path, errors="replace") as f:
        for ln in f:
            m = head_re.match(ln)
            if m:
                module = m.group(2) if m.group(1) == "Line" else None
                blocks = []
                continue
            if module is None:
                continue
            b = block_re.match(ln)
            if b:
                blocks.append((int(b.group(2)), b.group(1)))
                continue
            m = line_re.match(ln)
            if m:
                lineno, cov, tot = map(int, m.groups())
                # skip lines belonging to `initial` blocks: they are simulation-only
                # scaffolding (register init / if(reset)-at-time-zero) that never
                # exists in silicon and can never execute under the svsim reset
                # sequence — leaving them in permanently floors the metric.
                owner = None
                for start, typ in blocks:
                    if start <= lineno and (owner is None or start > owner[0]):
                        owner = (start, typ)
                if owner and owner[1] == "INITIAL":
                    continue
                data.setdefault(module, {}).setdefault(suite, {})[lineno] = (cov, tot)

def pct(cov, tot):
    return 100.0 * cov / tot if tot else 0.0

for module, suites in sorted(data.items()):
    tables = list(suites.values())
    if len(tables) == 1:
        t = tables[0]
        p = pct(sum(min(c, n) for c, n in t.values()), sum(n for _, n in t.values()))
        print(f"{module} {p:.2f} S")
        continue
    keys = set(tables[0].keys())
    aligned = all(set(t.keys()) == keys for t in tables) and all(
        len({t[k][1] for t in tables}) == 1 for k in keys)
    if aligned:
        covered = sum(min(tables[0][k][1], max(t[k][0] for t in tables)) for k in keys)
        total = sum(tables[0][k][1] for k in keys)
        print(f"{module} {pct(covered, total):.2f} U")
    else:
        best = max(pct(sum(min(c, n) for c, n in t.values()),
                       sum(n for _, n in t.values())) for t in tables)
        print(f"{module} {best:.2f} F")
PYEOF

{
  echo "Area verification map (generated $(date '+%Y-%m-%d %H:%M'))"
  echo "BEST   = best single-suite SCORE (all enabled metrics)"
  echo "LINE-U = LINE coverage union across all suites, EXCLUDING simulation-only"
  echo "         initial-block lines (register-init scaffolding that cannot execute"
  echo "         under the svsim reset sequence). '*' = suites use different"
  echo "         parameters, so this falls back to the best single suite; '--' = no"
  echo "         line items in this module"

  for area_dir in "$SCALA_DIR"/src/uciedigital/*/ "$SCALA_DIR"/src/tilelink/ "$SCALA_DIR"/src/phy/; do
    [ -d "$area_dir" ] || continue
    area=$(basename "$area_dir")

    # All Chisel Module classes declared in this area's sources.
    # Declarations often wrap before "extends Module", so flatten newlines first
    # (portable across grep implementations — this machine's grep is ugrep).
    MODULES=$(find "$area_dir" -name "*.scala" -exec cat {} + 2>/dev/null \
              | tr '\n' ' ' \
              | grep -oE "class +[A-Za-z0-9_]+[^{}]*extends +[A-Za-z0-9_.]*(Raw)?Module\b[^A-Za-z0-9_]" \
              | grep -oE "^class +[A-Za-z0-9_]+" \
              | awk '{print $2}' | sort -u)
    [ -z "$MODULES" ] && continue

    echo
    echo "===== $area ($(echo "$MODULES" | wc -l) modules) ====="

    tested=0
    total=0
    ROWS=""
    UNTESTED=""
    for m in $MODULES; do
      total=$((total + 1))
      best="" ; suites=""
      for rep in "$COV_DIR"/reports/*/modlist.txt; do
        s=$(basename "$(dirname "$rep")")
        # match "M" or chisel-dedup "M_<n>"; excludes verification layer rows
        score=$(awk -v m="$m" '
          $NF == m || $NF ~ ("^" m "_[0-9]+$") {
            if ($1 + 0 > best + 0 || best == "") best = $1
          }
          END { print best }' "$rep")
        if [ -n "$score" ]; then
          suites="$suites,$s"
          if [ -z "$best" ] || awk -v a="$score" -v b="$best" 'BEGIN{exit !(a+0>b+0)}'; then
            best=$score
          fi
        fi
      done
      if [ -n "$best" ]; then
        tested=$((tested + 1))
        # line union: best value among the module and its dedup variants
        lineu=$(awk -v m="$m" '
          $1 == m || $1 ~ ("^" m "_[0-9]+$") {
            if ($2 + 0 >= best + 0) { best = $2; flag = $3 }
          }
          END { if (best != "") printf "%s%s", best, (flag == "F" ? "*" : "") }' \
          "$UNION_MAP")
        [ -z "$lineu" ] && lineu="--"
        ROWS="$ROWS$(printf '%7s %9s  %-28s %s' "$best" "$lineu" "$m" "${suites#,}")\n"
      else
        UNTESTED="$UNTESTED      --        --  $m\n"
      fi
    done

    echo "   BEST    LINE-U  MODULE                       TESTED-BY"
    [ -n "$ROWS" ] && printf "%b" "$ROWS" | sort -rn | sed 's/^/ /'
    [ -n "$UNTESTED" ] && { echo "  ---- NOT TESTED ----"; printf "%b" "$UNTESTED"; }
    echo "  => $tested / $total modules tested"
  done
} | tee "$COV_DIR/area_summary.txt"

rm -f "$UNION_MAP"
