# ============================================================================
# Makefile for Verilog-AMS Clocking Testbenches
# ============================================================================
#
# Description:
#   Automated build system for running Xcelium simulations on BBPLL, DCDL,
#   and S2D behavioral models.
#
# Requirements:
#   - Cadence Xcelium simulator (xrun must be in PATH)
#   - Verilog-AMS support enabled
#
# Usage:
#   make all          - Run all testbenches
#   make bbpll        - Run BBPLL testbench only
#   make dcdl         - Run DCDL testbench only
#   make s2d          - Run S2D testbench only
#   make bbpll-gui    - Run BBPLL with SimVision GUI
#   make dcdl-gui     - Run DCDL with SimVision GUI
#   make s2d-gui      - Run S2D with SimVision GUI
#   make clean        - Remove all simulation output directories
#   make help         - Show this help message
#
# ============================================================================

# Xcelium command and options
XRUN := xrun
XRUN_OPTS := -64bit \
             -timescale 1ps/1fs \
             -xmlibdirname worklib \
             -access +rwc \
             -amsconnrules ConnRules_full_fast

# Paths
TB_DIR := ..
VAMS_DIR := ../..

# Source files
CONSTANTS := $(VAMS_DIR)/constants.vams
BBPLL_SRC := $(VAMS_DIR)/bbpll.vams
DCDL_SRC := $(VAMS_DIR)/dcdl.vams
S2D_SRC := $(VAMS_DIR)/s2d.vams
CLOCK_DIST_SRC := $(VAMS_DIR)/clock_distribution.vams

# Testbenches
BBPLL_TB := $(TB_DIR)/bbpll_tb.vams
DCDL_TB := $(TB_DIR)/dcdl_tb.vams
S2D_TB := $(TB_DIR)/s2d_tb.vams
CLOCK_DIST_TB := $(TB_DIR)/clock_distribution_tb.vams

# Probe files
BBPLL_PROBE := bbpll_probe.tcl
DCDL_PROBE := dcdl_probe.tcl
S2D_PROBE := s2d_probe.tcl
CLOCK_DIST_PROBE := clock_distribution_probe.tcl

# ============================================================================
# Targets
# BBPLL testbench
bbpll:
	@echo "=========================================="
	@echo "Running BBPLL Testbench with xrun..."
	@echo "=========================================="
	$(XRUN) $(XRUN_OPTS) \
		$(CONSTANTS) \
		$(BBPLL_SRC) \
		$(BBPLL_TB) \
		-top bbpll_tb \
		-ams clocking_ams_control_file.scs \
		-input $(BBPLL_PROBE)
	@echo "BBPLL simulation completed. Waveforms: bbpll_waves.shm"
	@echo ""

# DCDL testbench
dcdl:
	@echo "=========================================="
	@echo "Running DCDL Testbench with xrun..."
	@echo "=========================================="
	$(XRUN) $(XRUN_OPTS) \
		$(CONSTANTS) \
		$(DCDL_SRC) \
		$(DCDL_TB) \
		-top dcdl_tb \
		-ams clocking_ams_control_file.scs \
		-input $(DCDL_PROBE)
	@echo "DCDL simulation completed. Waveforms: dcdl_waves.shm"
	@echo ""

# S2D testbench
s2d:
	@echo "=========================================="
	@echo "Running S2D Testbench with xrun..."
	@echo "=========================================="
	$(XRUN) $(XRUN_OPTS) \
		$(CONSTANTS) \
		$(S2D_SRC) \
		$(S2D_TB) \
		-top s2d_tb \
		-ams clocking_ams_control_file.scs \
		-input $(S2D_PROBE)
	@echo "S2D simulation completed. Waveforms: s2d_waves.shm"
	@echo ""

# Clock Distribution testbench
clock_dist:
	@echo "=========================================="
	@echo "Running Clock Distribution Testbench with xrun..."
	@echo "=========================================="
	$(XRUN) $(XRUN_OPTS) \
		$(CONSTANTS) \
		$(CLOCK_DIST_SRC) \
		$(CLOCK_DIST_TB) \
		-top clock_distribution_tb \
		-ams clocking_ams_control_file.scs \
		-input $(CLOCK_DIST_PROBE)
	@echo "Clock Distribution simulation completed. Waveforms: clock_distribution_waves.shm"
	@echo ""

# GUI versions
bbpll-gui:
	@echo "=========================================="
	@echo "Running BBPLL Testbench with GUI..."
	@echo "=========================================="
	$(XRUN) $(XRUN_OPTS) \
		$(CONSTANTS) \
		$(BBPLL_SRC) \
		$(BBPLL_TB) \
		-top bbpll_tb \
		-gui \
		-input $(BBPLL_PROBE)

dcdl-gui:
	@echo "=========================================="
	@echo "Running DCDL Testbench with GUI..."
	@echo "=========================================="
	$(XRUN) $(XRUN_OPTS) \
		$(CONSTANTS) \
		$(DCDL_SRC) \
		$(DCDL_TB) \
		-top dcdl_tb \
		-gui \
		-input $(DCDL_PROBE)

s2d-gui:
	@echo "=========================================="
	@echo "Running S2D Testbench with GUI..."
	@echo "=========================================="
	$(XRUN) $(XRUN_OPTS) \
		$(CONSTANTS) \
		$(S2D_SRC) \
		$(S2D_TB) \
		-top s2d_tb \
		-gui \
		-input $(S2D_PROBE)

clock_dist-gui:
	@echo "=========================================="
	@echo "Running Clock Distribution Testbench with GUI..."
	@echo "=========================================="
	$(XRUN) $(XRUN_OPTS) \
		$(CONSTANTS) \
		$(CLOCK_DIST_SRC) \
		$(CLOCK_DIST_TB) \
		-top clock_distribution_tb \
		-gui \
		-input $(CLOCK_DIST_PROBE)

# Clean all simulation outputs
clean:
	@echo "Cleaning simulation outputs..."
	@rm -rf worklib *.log *.shm *.diag INCA_libs xcelium.d .simvision xrun.history .cadence
	@echo "Clean complete."

# Help message
help:
	@echo "=========================================="
	@echo "Verilog-AMS Clocking Testbench Makefile"
	@echo "=========================================="
	@echo ""
	@echo "Available targets:"
	@echo "  make all          - Run all testbenches (default)"
	@echo "  make bbpll        - Run BBPLL testbench"
	@echo "  make dcdl         - Run DCDL testbench"
	@echo "  make s2d          - Run S2D testbench"
	@echo "  make clock_dist   - Run Clock Distribution testbench"
	@echo "  make bbpll-gui    - Run BBPLL with SimVision GUI"
	@echo "  make dcdl-gui     - Run DCDL with SimVision GUI"
	@echo "  make s2d-gui      - Run S2D with SimVision GUI"
	@echo "  make clock_dist-gui - Run Clock Distribution with SimVision GUI"
	@echo "  make clean        - Remove all simulation outputs"
	@echo "  make help         - Show this help message"
	@echo ""
	@echo "Testbenches:"
	@echo "  BBPLL: $(BBPLL_TB)"
	@echo "  DCDL:  $(DCDL_TB)"
	@echo "  S2D:   $(S2D_TB)"
	@echo "  Clock Distribution: $(CLOCK_DIST_TB)"

	@echo "=========================================="

# ============================================================================
# End of Makefile
# ============================================================================
