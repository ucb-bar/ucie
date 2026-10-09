# Runs one bench by hand, the way `rs/src/verilog/mod.rs` runs it for
# `cargo test`: the same xrun options, every source under `verilog/common`, and
# one level's analog models out of `verilog/models`. `cargo test` is how the
# benches are meant to be run; this is for running one outside it, to look at
# its waveforms in SimVision.
#
#   make                              # phy_tb against models/eye
#   make TB=training_tb LEVEL=circuit
#
# See README.md for the environment it needs.

TB ?= phy_tb
LEVEL ?= eye

VERILOG_DIR := ../verilog
AHDL_DIR := $(XCELIUM_HOME)/tools.lnx86/spectre/etc/ahdl

# Every level provides the same cells, so a level is nothing more than which
# directory under `models` is compiled next to `common`. xrun compiles in the
# order it is given files, and a package has to come before whatever imports
# it, so `*_pkg.sv` goes first.
SRC := $(shell find $(VERILOG_DIR)/common $(VERILOG_DIR)/models/$(LEVEL) \
           -type f \( -name '*.sv' -o -name '*.v' -o -name '*.vams' \) | sort)
SRC := $(filter %_pkg.sv,$(SRC)) $(filter-out %_pkg.sv,$(SRC))

XRUN_OPTS := -allowredefinition \
             -dmsaoi \
             -sv_ms \
             -timescale 1ps/100fs \
             -spectre_args "+preset=mx +mt=32 -ahdllint=warn" \
             -access +rwc \
             -incdir $(VERILOG_DIR) \
             -incdir $(AHDL_DIR)

.PHONY: sim clean
sim:
	@test -d $(VERILOG_DIR)/models/$(LEVEL) || \
		{ echo "no such level: $(LEVEL) (see $(VERILOG_DIR)/models)"; exit 1; }
	xrun $(XRUN_OPTS) \
		-top $(TB) \
		-input probe.tcl \
		$(AHDL_DIR)/disciplines.vams \
		$(AHDL_DIR)/constants.vams \
		$(VERILOG_DIR)/constants.vams \
		$(SRC) \
		amscf.scs
	@echo "Waveforms: waves.shm"

clean:
	rm -rf xcelium.d waves.shm *.log *.diag *.history .simvision .cadence \
		amscf.ams amscomm.list
