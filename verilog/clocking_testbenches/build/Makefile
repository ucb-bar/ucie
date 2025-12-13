# ============================================================================
# Makefile for Verilog-AMS Clocking Testbenches
# ============================================================================
#
# Description:
#   Automated build system for running Spectre simulations on BBPLL, DCDL,
#   and S2D behavioral models.
#
# Requirements:
#   - Cadence Spectre simulator (must be in PATH or set via SPECTRE variable)
#   - Verilog-AMS support enabled
#
# Usage:
#   make all          - Run all testbenches
#   make bbpll        - Run BBPLL testbench only
#   make dcdl         - Run DCDL testbench only
#   make s2d          - Run S2D testbench only
#   make clean        - Remove all simulation output directories
#   make help         - Show this help message
#
# ============================================================================

# Check if Spectre is available
ifndef SPECTRE
SPECTRE := $(shell which spectre 2>/dev/null)
ifeq ($(SPECTRE),)
$(error ERROR: Spectre simulator not found. Please set SPECTRE environment variable or add spectre to PATH)
endif
endif

# Spectre command with APS (Spectre X) enabled
SPECTRE_CMD := $(SPECTRE) -64 ++aps

# Testbench directory (build/ - run simulations here)
TB_DIR := .

# Testbench source files (one level up)
SRC_DIR := ..
BBPLL_SRC := $(SRC_DIR)/bbpll_tb.scs
DCDL_SRC := $(SRC_DIR)/dcdl_tb.scs
S2D_SRC := $(SRC_DIR)/s2d_tb.scs

# Output directories
BBPLL_OUT := bbpll_tb.raw
DCDL_OUT := dcdl_tb.raw
S2D_OUT := s2d_tb.raw

# Log files
BBPLL_LOG := bbpll_tb.log
DCDL_LOG := dcdl_tb.log
S2D_LOG := s2d_tb.log

# ============================================================================
# Targets
# ============================================================================

.PHONY: all bbpll dcdl s2d clean help check_spectre

# Default target: run all testbenches
all: check_spectre bbpll dcdl s2d
	@echo ""
	@echo "=========================================="
	@echo "All testbenches completed successfully!"
	@echo "=========================================="
	@echo "Results:"
	@echo "  BBPLL: $(BBPLL_OUT)  (log: $(BBPLL_LOG))"
	@echo "  DCDL:  $(DCDL_OUT)  (log: $(DCDL_LOG))"
	@echo "  S2D:   $(S2D_OUT)  (log: $(S2D_LOG))"
	@echo "=========================================="

# Check Spectre availability
check_spectre:
	@echo "Checking Spectre simulator..."
	@echo "Using: $(SPECTRE)"
	@$(SPECTRE) -V | head -1
	@echo ""

# Run BBPLL testbench
bbpll: check_spectre
	@echo "=========================================="
	@echo "Running BBPLL Testbench..."
	@echo "=========================================="
	$(SPECTRE_CMD) $(BBPLL_SRC) 2>&1 | tee $(BBPLL_LOG)
	@echo ""
	@echo "BBPLL simulation completed. Log: $(BBPLL_LOG)"
	@echo ""

# Run DCDL testbench
dcdl: check_spectre
	@echo "=========================================="
	@echo "Running DCDL Testbench..."
	@echo "=========================================="
	$(SPECTRE_CMD) $(DCDL_SRC) 2>&1 | tee $(DCDL_LOG)
	@echo ""
	@echo "DCDL simulation completed. Log: $(DCDL_LOG)"
	@echo ""

# Run S2D testbench
s2d: check_spectre
	@echo "=========================================="
	@echo "Running S2D Testbench..."
	@echo "=========================================="
	$(SPECTRE_CMD) $(S2D_SRC) 2>&1 | tee $(S2D_LOG)
	@echo ""
	@echo "S2D simulation completed. Log: $(S2D_LOG)"
	@echo ""

# Clean all simulation outputs
clean:
	@echo "Cleaning simulation outputs..."
	@rm -rf *.raw *.raw+ *.ahdlSimDB
	@rm -f $(BBPLL_LOG) $(DCDL_LOG) $(S2D_LOG)
	@rm -f *.log *.log.* *.err
	@rm -f .spectre* .ahdl*
	@rm -f *.psf *.dsn *.init *.srf
	@echo "Clean complete."

# Help message
help:
	@echo "=========================================="
	@echo "Verilog-AMS Clocking Testbench Makefile"
	@echo "=========================================="
	@echo ""
	@echo "Available targets:"
	@echo "  make all          - Run all testbenches (default)"
	@echo "  make bbpll        - Run BBPLL testbench only"
	@echo "  make dcdl         - Run DCDL testbench only"
	@echo "  make s2d          - Run S2D testbench only"
	@echo "  make clean        - Remove all simulation outputs"
	@echo "  make help         - Show this help message"
	@echo ""
	@echo "Environment:"
	@echo "  SPECTRE=$(SPECTRE)"
	@echo ""
	@echo "Testbenches:"
	@echo "  BBPLL: $(BBPLL_SRC)"
	@echo "  DCDL:  $(DCDL_SRC)"
	@echo "  S2D:   $(S2D_SRC)"
	@echo "=========================================="

# ============================================================================
# End of Makefile
# ============================================================================
