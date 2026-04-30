UCIE_ROOT ?= $(abspath ../..)
SCALA_DIR ?= $(UCIE_ROOT)/scala
BUILD_DIR ?= $(UCIE_ROOT)/build/d2d-dv
GEN_DIR ?= $(BUILD_DIR)/generated
SIM_DIR ?= $(BUILD_DIR)/sim

DATA_BYTES ?= 32
SIDEBAND_WIDTH ?= 32

ELAB_MAIN ?= edu.berkeley.cs.uciedigital.d2dadapter.D2DAdapterDvElaborate
ELAB_TOP ?= D2DAdapterDvTop
TB_TOP ?= $(ELAB_TOP)

VCS ?= vcs -full64
VCS_OPTS ?= \
	-sverilog \
	+v2k \
	-timescale=1ns/1ps \
	+lint=all,noVCDE,noONGS,noUI \
	+vcs+lic+wait \
	-debug_access+all \
	-kdb \
	-lca

DV_SRCS ?=
RTL_FILELIST := $(SIM_DIR)/rtl.f
SIMV := $(SIM_DIR)/simv

.PHONY: elab filelist vcs run clean

elab:
	cd $(SCALA_DIR) && ./mill -i d2ddv.runMain $(ELAB_MAIN) \
		--target-dir $(GEN_DIR) \
		--data-bytes $(DATA_BYTES) \
		--sideband-width $(SIDEBAND_WIDTH)

filelist: elab
	mkdir -p $(SIM_DIR)
	find $(GEN_DIR) -type f \( -name '*.sv' -o -name '*.v' \) | sort > $(RTL_FILELIST)

vcs: filelist
	$(VCS) $(VCS_OPTS) \
		-f $(RTL_FILELIST) \
		$(DV_SRCS) \
		-top $(TB_TOP) \
		-o $(SIMV) \
		-l $(SIM_DIR)/vcs_compile.log

run: vcs
	$(SIMV) -l $(SIM_DIR)/vcs_run.log

clean:
	rm -rf $(BUILD_DIR)
