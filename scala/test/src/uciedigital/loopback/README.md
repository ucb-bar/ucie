# Loopback bring-up testbenches

Three testbenches that wire two dies together and walk the link up from reset,
one LTSM state per test. Each one covers a different slice of the stack, so a
failure in the widest testbench can be located in a narrower one.

Every test is a cold start and climbs to the state it names. The tests are
independent, so the first failing test names the first thing the link cannot do.

Only die 0 takes the software trigger. Die 1 has to wake on the sideband clock
pattern die 0 transmits, which is the arrival order two chiplets actually see.
A testbench that starts both dies together would hide every bug in the remote
wake path.

## The three testbenches

| Testbench | DUT | Driven by | Stages |
|---|---|---|---|
| `LogPhyStagedBringupTest` | two `LogicalPhy` | testbench pins | 9 |
| `UcieDigitalStagedBringupTest` | two `ProtocolLayer` + `D2DAdapter` + `LogicalPhy` | testbench pins | 11 |
| `UcieMmioBringupTest` | two `UcieDigitalTop` | TileLink register writes | 12 |

### LogPhyStagedBringupTest

Two `LogicalPhy` instances cross-wired at the analog boundary, with no adapter
above them. The RDI handshakes are auto-acked by the harness.

This is where a training failure gets located. It walks the LTSM one state at a
time, from RESET through SBINIT, MBINIT, MBTRAIN and LINKINIT to ACTIVE, then
carries RDI words across the mainband in both directions.

### UcieDigitalStagedBringupTest

The full stack built by hand, two of everything. A real `D2DAdapter` drives the
RDI here, so the clock and stall handshakes and the cfg credits are hardware
rather than testbench pokes.

PHY training is one stage rather than nine, because the LogPhy testbench already
covers it. What this adds is everything above the RDI: the ADV_CAP exchange,
protocol negotiation, the FDI handshakes and protocol beats crossing the link.

### UcieMmioBringupTest

Two `UcieDigitalTop` instances with their register blocks, driven by one
TileLink master per die and nothing else. A stage that passes here is a stage
software can reach.

One register write on die 0 brings the whole link up. Die 1 wakes on the
sideband pattern and opens its own FDI without ever being written.

## Harnesses

Each testbench has a harness that cross-wires the two dies and exposes what the
tests observe. The analog macro is not modelled, so `pllLock` and
`clocksUngatedAndStable` are tied high.

Observation goes through one packed word rather than one port per signal.
Registering many scopes makes the generated Verilator model fault at time zero,
so `DieFlag` and `MmioFlag` hold the bit positions. The MMIO harness reads its
signals with `BoringUtils` taps so the shipping top gains no ports for the sake
of the test.

The chip-facing data ports are behind `exposeDataPath`. With them tied off the
simulator folds away the beat packing, which every stage would otherwise pay for
across the reset wait.

## Running them

```
./mill test.testOnly edu.berkeley.cs.uciedigital.loopback.LogPhyStagedBringupTest
./mill test.testOnly edu.berkeley.cs.uciedigital.loopback.UcieDigitalStagedBringupTest
./mill test.testOnly edu.berkeley.cs.uciedigital.loopback.UcieMmioBringupTest
```

One stage on its own:

```
./mill test.testOnly edu.berkeley.cs.uciedigital.loopback.LogPhyStagedBringupTest -- -z "Stage 4"
```

Each test pays a 3.2M-cycle reset wait, so a full testbench takes several
minutes. CI does not run these, it only type-checks them.

## What these do not cover

The MBINIT.PARAM exchange agrees because both dies advertise the same all-zero
parameters. Nothing is really negotiated.

The MBTRAIN vref and centering substates complete immediately, because
`PhyLaneTrainer` has no calibration hardware to drive. Revisit those stages once
the analog knobs are wired.

Payloads are tagged deterministic patterns rather than random data, so a swapped
beat, a stale beat and a lane permutation each fail differently. Data-dependent
failures need a random stage that does not exist yet.
