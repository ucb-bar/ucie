# Xcelium UCIePHY Simulation Project

This directory contains the Xcelium simulation setup for the UCIePHY (Universal Chiplet Interconnect Express Physical Layer) testbench. The simulation uses mixed-signal (AMS + SystemVerilog) modeling to verify the PHY components.

## Prerequisites

Before running the simulation, you **must** define the following environment variables to point to your Cadence tool installations:

- **SPECTRE_HOME**: Path to the Spectre installation directory.
- **AMS_HOME**: Path to the Xcelium AMS installation directory.

Without these variables, the Makefile will fail to locate necessary libraries and disciplines.

## Running the Simulation

1. Ensure you are in the `xcelium` directory.
2. Set the environment variables as described above.
3. Run the simulation:

   ```bash
   make phy
   ```

   This will compile all AMS and SystemVerilog sources, elaborate the design, and run the testbench with the top-level module `phy_tb`.

## Cleaning Up

To remove simulation artifacts (libraries, logs, waveforms):

```bash
make clean
```

## Files Overview

- **Makefile**: Defines the simulation flow using `xrun`.
- **amscf.scs**: AMS control file for Spectre integration.
- **probe.tcl**: Tcl script for waveform probing.
- **../verilog/**: Source files (AMS and SV) for the PHY components.

## Notes

- The simulation uses mixed-signal mode (`-sv_ms`) to handle both analog (AMS) and digital (SystemVerilog) components.
- Waveforms are saved to `phy_waves.shm` upon completion.
- If you encounter permission or path errors, verify the environment variables and tool installations.
