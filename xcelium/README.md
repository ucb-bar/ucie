# Xcelium AMS setup

The files here are shared by every mixed-signal bench under `../verilog`:

- **amscf.scs**: the AMS control file -- the transient analysis and the
  connect-module rules between the analog models and the digital logic.
- **probe.tcl**: probes every signal into `waves.shm` and runs the bench.
- **Makefile**: runs one bench by hand.

The benches are normally run with `cargo test` from `../rs`, which compiles each
one in its own work directory against each level of the analog models; see
`../verilog/README.md`. The Makefile runs the same thing outside it, for when you
want a bench's waveforms in SimVision.

## Prerequisites

- **XCELIUM_HOME** set to the Xcelium installation, with `xrun` on `PATH`.
- A real Spectre ahead of anything else called `spectre` on `PATH`. xrun's AMS
  flow looks for `amsspice` next to the first `spectre` it finds, and Liberate
  ships a `spectre` without one:

  ```bash
  export PATH=/tools/cadence/SPECTRE/SPECTRE251/bin:$PATH
  ```

## Running a bench

```bash
make                                # phy_tb against models/eye
make TB=training_tb                 # another bench
make TB=phy_tb LEVEL=circuit        # another level of the analog models
make clean
```

`TB` is any top-level bench module under `../verilog` (`phy_tb`, `training_tb`,
`dff_tb`, ...), and `LEVEL` any directory under `../verilog/models`. Waveforms
land in `waves.shm`.
