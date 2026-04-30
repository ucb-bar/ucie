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
make vcs
```

Run it:

```bash
make run
```

By default, VCS compiles the emitted `D2DAdapterDvTop` under the stable
SystemVerilog wrapper `ucie_d2d_dv_top`. The wrapper exposes fixed `fdi` and
`rdi` interface instances for drivers, monitors, and SVA checkers.

Override widths as needed:

```bash
make elab DATA_BYTES=64 SIDEBAND_WIDTH=32
```

## Next Integration Step

Add new tests by extending or replacing `dv/d2d/tb/ucie_d2d_dv_top.sv`, or by
adding extra files through `DV_SRCS`. Keep tests and assertions pointed at the
wrapper/interface signals rather than generated internal hierarchy.
