#!/bin/bash
# VCS coverage run + merge for ucie tests. Lives in scala/verdi_coverage/;
# all merged outputs stay in this directory.
#
# Usage:
#   ./run_verdi_coverage.sh                                            # full suite
#   ./run_verdi_coverage.sh --clean                                    # clean and run full suite
#   ./run_verdi_coverage.sh edu.berkeley.cs.uciedigital.logphy.UcieLFSRTest ...
#                                                                      # specific suites
#   ./run_verdi_coverage.sh --merge-only                               # just re-merge existing vdbs
#
# Per-test coverage DBs land in build/chiselsim/<Test>/<scenario>/workdir-vcs/simulation.vdb
# (fixed by ChiselSim). The merge stage produces, in this directory:
#   suites/<Suite>.vdb    <- ACCURATE per-suite merged DB (open these in Verdi)
#   reports/<Suite>/      <- per-suite urg report (all modules, line-annotated source
#                            in modinfo.txt / mod*.html)
#   modules_summary.txt   <- per-module scores of every suite, in one file
#   area_summary.txt      <- per-subsystem map: every RTL module, tested or not
#   merged.vdb, urgReport <- global all-suite merge: single trend number ONLY.
#                            Each suite elaborates a DIFFERENT design under the same
#                            top name (svsimTestbench/dut), so urg's cross-design
#                            merge silently drops non-matching module definitions.
#                            For per-module / per-line analysis use suites/ and
#                            reports/, never the global DB.
#
# View:  ./view_coverage.sh <Suite>    (no arg: global merged.vdb)

set -u
COV_DIR="$(cd "$(dirname "$0")" && pwd)"
# guard: COV_DIR feeds rm -rf paths below — never proceed with an empty value
[ -n "$COV_DIR" ] || { echo "ERROR: cannot resolve script directory"; exit 1; }
SCALA_DIR="$(dirname "$COV_DIR")"
cd "$SCALA_DIR" || exit 1

if [ "${1:-}" = "--clean" ]; then
  shift
  echo "Cleaning build/chiselsim and previous coverage outputs"
  rm -rf build/chiselsim
  # also drop every generated output, so nothing stale survives an aborted run
  rm -rf "$COV_DIR/suites" "$COV_DIR/reports" "$COV_DIR/merged.vdb" "$COV_DIR/urgReport" \
         "$COV_DIR/modules_summary.txt" "$COV_DIR/area_summary.txt"
fi

# svsim invokes $VCS_HOME/bin/vcs directly (PATH is rebuilt by mill's test fork,
# so PATH tricks don't survive). The shim VCS_HOME interposes a vcs wrapper that
# restores the real VCS_HOME and puts a C++17-capable g++ (conda gcc-15, -no-pie)
# ahead of system g++ 4.8 for VCS csrc builds. Machine-specific; skipped elsewhere.
if [ -d /home/sangwoo/tools/vcs-home-shim ]; then
  export VCS_HOME=/home/sangwoo/tools/vcs-home-shim
fi

if [ "${1:-}" != "--merge-only" ]; then
  # --no-daemon: the mill daemon caches its startup environment, so
  # UCIE_SIM_BACKEND only reliably reaches the forked test JVM without it.
  if [ $# -gt 0 ]; then
    UCIE_SIM_BACKEND=vcs ./mill --no-daemon test.testOnly "$@" \
      || echo "WARN: some tests failed (continuing to merge coverage)"
  else
    UCIE_SIM_BACKEND=vcs ./mill --no-daemon test \
      || echo "WARN: some tests failed (continuing to merge coverage)"
  fi
fi

if [ ! -d build/chiselsim ]; then
  echo "ERROR: build/chiselsim not found — did the VCS run produce coverage?"
  exit 1
fi

# --- Stage 1: accurate per-suite merges + reports ----------------------------
# Merge unit: scenarios of a suite that elaborate the SAME design. Most suites
# use one design for every scenario and merge as a single unit, but a suite can
# drive several different DUTs (e.g. SidebandChannelRandomTest tests the
# Protocol/D2D/LogPhy channels in one class); merging those together makes urg
# silently drop every design but the first. Scenarios are therefore grouped by
# a design fingerprint (the sorted list of generated .sv names), and each group
# gets its own <Suite>__<scenario-prefix> entry.
rm -rf "$COV_DIR/suites" "$COV_DIR/reports"
mkdir -p "$COV_DIR/suites" "$COV_DIR/reports"
TOTAL=0
SUITES=()
for sd in build/chiselsim/*/; do
  s=$(basename "$sd")
  declare -A GROUP_VDBS=()
  declare -A GROUP_LABEL=()
  n=0
  while IFS= read -r v; do
    scen="${v%/workdir-vcs/simulation.vdb}"
    # fingerprint by CONTENT, not just file names: scenarios with the same module
    # set but different parametrization (e.g. 8 vs 16 lanes) are different designs,
    # and merging them makes urg drop the extra instances' data (CMR-VCINF).
    fp=$(find "$scen/primary-sources" -maxdepth 1 -name "*.sv" 2>/dev/null | sort \
         | xargs cat 2>/dev/null | md5sum | cut -d' ' -f1)
    GROUP_VDBS[$fp]="${GROUP_VDBS[$fp]:-}$SCALA_DIR/$v"$'\n'
    # remember one scenario name per group for labeling
    [ -z "${GROUP_LABEL[$fp]:-}" ] && GROUP_LABEL[$fp]=$(basename "$scen" | cut -c1-32)
    n=$((n + 1))
  done < <(find "$sd" -type d -name simulation.vdb 2>/dev/null | sort)
  [ "$n" -eq 0 ] && continue
  TOTAL=$((TOTAL + n))

  ngroups=${#GROUP_VDBS[@]}
  for fp in "${!GROUP_VDBS[@]}"; do
    # short fingerprint suffix keeps labels unique when scenario-name prefixes collide
    if [ "$ngroups" -eq 1 ]; then label="$s"; else label="${s}__${GROUP_LABEL[$fp]}-${fp:0:6}"; fi
    URG_ARGS=()
    g=0
    while IFS= read -r vv; do
      [ -n "$vv" ] && { URG_ARGS+=(-dir "$vv"); g=$((g + 1)); }
    done <<< "${GROUP_VDBS[$fp]}"
    SUITES+=("$label")
    echo "[$label] merging $g vdbs"
    # urg mishandles -dbname paths containing directories (it collapses them to
    # the parent name), so run from suites/ with a plain db name instead.
    ( cd "$COV_DIR/suites" \
      && urg -full64 "${URG_ARGS[@]}" -dbname "$label" \
          -report "$COV_DIR/reports/$label" -format both > /dev/null ) \
      || echo "WARN: urg failed for $label"
  done
  unset GROUP_VDBS GROUP_LABEL
done

if [ "$TOTAL" -eq 0 ]; then
  echo "ERROR: no simulation.vdb found under build/chiselsim — did the VCS run produce coverage?"
  exit 1
fi

# --- Combined per-module summary across all suites ---------------------------
{
  echo "Per-suite module coverage (accurate; generated $(date '+%Y-%m-%d %H:%M'))"
  echo "(each section carries its own column header; ASSERT column appears only where assertions exist)"
  for s in "${SUITES[@]}"; do
    echo
    echo "===== $s ====="
    # module table of the suite report, without the leading blurb
    awk '/^-{10,}/{on=1; next} on' "$COV_DIR/reports/$s/modlist.txt" 2>/dev/null
  done
} > "$COV_DIR/modules_summary.txt"

# --- Area verification map (per-subsystem tested/untested module table) ------
"$COV_DIR/area_summary.sh" > /dev/null 2>&1 \
  && echo "area map    -> $COV_DIR/area_summary.txt" \
  || echo "WARN: area_summary.sh failed"

# --- Stage 2: global merge (trend number only — see header caveat) -----------
URG_ARGS=()
while IFS= read -r v; do URG_ARGS+=(-dir "$SCALA_DIR/$v"); done \
  < <(find build/chiselsim -type d -name simulation.vdb 2>/dev/null | sort)
echo "[global] merging $TOTAL vdbs (trend number only)"
( cd "$COV_DIR" \
  && urg -full64 "${URG_ARGS[@]}" -dbname merged -report urgReport -format both > /dev/null ) \
  || echo "WARN: global urg merge failed"

echo ""
echo "Done. Outputs in $COV_DIR:"
echo "  reports/<Suite>/dashboard.txt|.html   per-suite coverage (accurate)"
echo "  modules_summary.txt                   all suites' module scores in one file"
echo "  area_summary.txt                      per-subsystem tested/untested module map"
echo "  ./view_coverage.sh <Suite>            Verdi GUI on an accurate suite DB"
echo "  ./view_coverage.sh                    Verdi GUI on the global merged DB (trend only)"
