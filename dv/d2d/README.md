# D2D Adapter DV Flow

This directory is the entry point for SystemVerilog verification of the
elaborated D2D adapter.

The flow is intentionally split into two parts:

1. Elaborate the Chisel D2D adapter DV top with Mill.
2. Compile the emitted SystemVerilog plus DV sources with VCS.

The D2D adapter uses a narrow Mill module, `d2ddv`, so adapter elaboration does
not require the TileLink/Chipyard dependencies used by the full repo tests.

## Commands

From this directory:

```bash
make elab
```

Elaboration uses Mill and requires Java 17 or newer. If you have sourced the
VCS tool paths and they put Java 8 first in `PATH`, run elaboration in a fresh
shell or restore Java 17 before running `make elab`.

This runs:

```bash
cd ../../scala
./mill -i d2ddv.runMain \
  edu.berkeley.cs.uciedigital.d2dadapter.D2DAdapterDvElaborate \
  --target-dir ../build/d2d-dv/generated \
  --data-bytes 32 \
  --sideband-width 32
```

Build a VCS simulator from the emitted RTL:

```bash
source ../../tool_path.sh
make vcs
```

Run it:

```bash
make run
```

Run a specific directed test:

```bash
make run TEST=smoke
```

By default, VCS compiles the emitted `D2DAdapterDvTop` under the stable
SystemVerilog wrapper `ucie_d2d_dv_top`. The wrapper exposes fixed `fdi` and
`rdi` interface instances for drivers, monitors, and SVA checkers.

Override widths as needed:

```bash
make elab DATA_BYTES=64 SIDEBAND_WIDTH=32
```

## Harness Layout

The harness is intentionally simple:

- `common/ucie_d2d_dv_pkg.sv` holds shared constants and small helper functions.
- `if/` holds the stable FDI/RDI interfaces and simple drive tasks.
- `checkers/` holds always-on assertions that should apply to every test.
- `tests/<name>_test.sv` defines the selected `ucie_d2d_test` module.
- `tb/ucie_d2d_dv_top.sv` owns clock/reset, DUT wiring, common checkers, and test instantiation.

Add a new test by creating `tests/<name>_test.sv` with a module named
`ucie_d2d_test`, then run:

```bash
make run TEST=<name>
```

Keep tests and assertions pointed at the wrapper/interface signals rather than
generated internal hierarchy.
