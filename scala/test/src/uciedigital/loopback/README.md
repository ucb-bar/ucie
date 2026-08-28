# Loopback bring-up testbenches

Four testbenches that wire two dies together and walk the link up from reset,
one LTSM state per test. Each one covers a different slice of the stack, so a
failure in the widest testbench can be located in a narrower one.

Every test is a cold start and climbs to the state it names. The tests are
independent, so the first failing test names the first thing the link cannot do.

Only die 0 takes the software trigger. Die 1 has to wake on the sideband clock
pattern die 0 transmits, which is the arrival order two chiplets actually see.
A testbench that starts both dies together would hide every bug in the remote
wake path.

## The four testbenches

| Testbench | DUT | Driven by | Stages |
|---|---|---|---|
| `LogPhyStagedBringupTest` | two `LogicalPhy` | testbench pins | 9 |
| `MmplStagedBringupTest` | two `MultiModulePhy` | testbench pins | 7 per configuration |
| `UcieDigitalStagedBringupTest` | two `ProtocolLayer` + `D2DAdapter` + `LogicalPhy` | testbench pins | 11 |
| `UcieMmioBringupTest` | two `UcieDigitalTop` | TileLink register writes | 12 |

### LogPhyStagedBringupTest

Two `LogicalPhy` instances cross-wired at the analog boundary, with no adapter
above them. The RDI handshakes are auto-acked by the harness.

This is where a training failure gets located. It walks the LTSM one state at a
time, from RESET through SBINIT, MBINIT, MBTRAIN and LINKINIT to ACTIVE, then
carries RDI words across the mainband in both directions.

### MmplStagedBringupTest

The same ladder as the LogPhy testbench, but each die is a `MultiModulePhy`: one
MMPL over two or four `LogicalPhy` instances presenting a single wide RDI
(spec 4.7). Every stage is checked on every module, because a multi-module link
can hold its modules in different states until the MMPL resolves them.

The two dies are cross-wired through a **module ID permutation**: die 0's module
at index `m` faces die 1's module at index `modulePairing(m)`. Each module
advertises its index as its module ID in MBINIT.PARAM, so a non-identity pairing
is the spec Figure 4-44 case where the remote link partner names its modules
differently. Three configurations run: two modules with matching IDs, two with
`M0` facing `M1`, and four with `M0` facing `M2` and `M1` facing `M3` -- the last
two from spec Table 5-27.

Because the link has one RDI state machine for all its modules (spec 3.5),
hosted in the MMPL, stage 6 also exercises that machine bringing RDI up over a
single module's sideband. Under a non-identity pairing the response comes back
on a different module, which is the spec 4.7.1.1 case where "a packet sent on a
given Module ID could be received on a different Module ID".

What this adds over the LogPhy testbench is the MMPL itself. Stage 3 checks each
module learned the right remote module ID, stage 5 walks the multi-module
MBTRAIN.LINKSPEED resolution end to end (every module reports what it sent and
received, the MMPL resolves, and every module exchanges the response it was
directed to), stage 6 checks the aggregate `pl_lnk_cfg` is the summed width, and
stage 7 carries a tagged word across. Stage 7 is the one that fails if the
transmit byte map ranks by the local module ID instead of the remote one.

Link training residency timeouts are shortened here through
`timeoutCyclesOverride`. The spec value is 8 ms, 6.4M cycles at 800 MHz with a
3.2M-cycle minimum RESET wait, which is far more than these checks need once
there are eight `LogicalPhy` instances in the simulation.

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
./mill test.testOnly edu.berkeley.cs.uciedigital.loopback.MmplStagedBringupTest
./mill test.testOnly edu.berkeley.cs.uciedigital.loopback.UcieDigitalStagedBringupTest
./mill test.testOnly edu.berkeley.cs.uciedigital.loopback.UcieMmioBringupTest
```

One multi-module configuration on its own:

```
./mill test.testOnly edu.berkeley.cs.uciedigital.loopback.MmplStagedBringupTest -- -z "four modules"
```

One stage on its own:

```
./mill test.testOnly edu.berkeley.cs.uciedigital.loopback.LogPhyStagedBringupTest -- -z "Stage 4"
```

Each test pays a 3.2M-cycle reset wait, so a full testbench takes several
minutes. `MmplStagedBringupTest` shortens the timeouts instead and runs in about
a minute for two modules and two for four. CI does not run any of these, it only
type-checks them.

## What these do not cover

The MBINIT.PARAM exchange agrees because both dies advertise the same all-zero
parameters. Nothing is really negotiated.

The MBTRAIN vref and centering substates complete immediately, because
`PhyLaneTrainer` has no calibration hardware to drive. Revisit those stages once
the analog knobs are wired.

Payloads are tagged deterministic patterns rather than random data, so a swapped
beat, a stale beat and a lane permutation each fail differently. Data-dependent
failures need a random stage that does not exist yet.

No multi-module test drives a module to fail training, so the MMPL width degrade,
speed degrade and module disable resolutions are covered by
`MmplLinkSpeedResolverTest` and `MmplTest` rather than end to end here. Reaching
them on a real link needs a way to inject lane errors, which `PhyLaneTrainer`
cannot do yet.
