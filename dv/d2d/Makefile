UCIE_ROOT ?= $(abspath ../..)
SCALA_DIR ?= $(UCIE_ROOT)/scala
BUILD_DIR ?= $(UCIE_ROOT)/build/d2d-dv
GEN_DIR ?= $(BUILD_DIR)/generated
SIM_DIR ?= $(BUILD_DIR)/sim

DATA_BYTES ?= 32
SIDEBAND_WIDTH ?= 32

ELAB_MAIN ?= edu.berkeley.cs.uciedigital.d2dadapter.D2DAdapterDvElaborate
ELAB_TOP ?= D2DAdapterDvTop
TB_TOP ?= ucie_d2d_dv_top
TEST ?= smoke
REGRESSION_TESTS ?= smoke

VCS ?= vcs -full64
VCS_OPTS ?= \
	-sverilog \
	+v2k \
	-timescale=1ns/1ps \
	+lint=all,noVCDE,noONGS,noUI \
	+vcs+lic+wait \
	-debug_access+all \
	+define+D2D_DV_DATA_BYTES=$(DATA_BYTES) \
	+define+D2D_DV_SIDEBAND_WIDTH=$(SIDEBAND_WIDTH) \
	-Mdir=$(SIM_DIR)/csrc \
	-kdb \
	-lca

TEST_SRCS ?= $(UCIE_ROOT)/dv/d2d/tests/$(TEST)_test.sv

DV_SRCS ?= \
	$(UCIE_ROOT)/dv/d2d/common/ucie_d2d_dv_pkg.sv \
	$(UCIE_ROOT)/dv/d2d/if/ucie_d2d_fdi_if.sv \
	$(UCIE_ROOT)/dv/d2d/if/ucie_d2d_rdi_if.sv \
	$(UCIE_ROOT)/dv/d2d/checkers/ucie_d2d_common_checkers.sv \
	$(TEST_SRCS) \
	$(UCIE_ROOT)/dv/d2d/tb/ucie_d2d_dv_top.sv
RTL_FILELIST := $(SIM_DIR)/rtl.f
SIMV := $(SIM_DIR)/simv-$(TEST)

.PHONY: elab force-elab vcs run regression clean

elab:
	@if [ -f "$(GEN_DIR)/$(ELAB_TOP).sv" ]; then \
		echo "Using existing elaborated RTL in $(GEN_DIR). Run 'make force-elab' to regenerate."; \
	else \
		$(MAKE) force-elab; \
	fi

force-elab:
	cd $(SCALA_DIR) && ./mill -i d2ddv.runMain $(ELAB_MAIN) \
		--target-dir $(GEN_DIR) \
		--data-bytes $(DATA_BYTES) \
		--sideband-width $(SIDEBAND_WIDTH)

$(RTL_FILELIST): elab
	mkdir -p $(SIM_DIR)
	find $(GEN_DIR) -type f \( -name '*.sv' -o -name '*.v' \) \
		! -path '$(GEN_DIR)/verification/*' | sort > $(RTL_FILELIST)

vcs: $(RTL_FILELIST)
	@test -f "$(TEST_SRCS)" || (echo "Missing TEST_SRCS=$(TEST_SRCS)"; exit 1)
	$(VCS) $(VCS_OPTS) \
		-f $(RTL_FILELIST) \
		$(DV_SRCS) \
		-top $(TB_TOP) \
		-o $(SIMV) \
		-l $(SIM_DIR)/vcs_compile_$(TEST).log

run: vcs
	$(SIMV) -l $(SIM_DIR)/vcs_run_$(TEST).log

regression:
	@for test in $(REGRESSION_TESTS); do \
		$(MAKE) run TEST=$$test || exit $$?; \
	done

clean:
	rm -rf $(BUILD_DIR)
