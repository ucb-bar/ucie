# PHY models

The analog PHY is modelled at more than one level of abstraction, because the
questions asked of it do not all need the same model. A bit-order bug in a
serializer needs no analog at all. Training needs an eye in front of the
receiver and a threshold in the middle of it, and nothing more. Sizing a driver
segment needs the driver's segments.

Every level presents the same cells with the same pins, so the level is chosen
by which files are compiled, and nothing above the cells changes.

```
verilog/
  constants.vams      shared parameters, including each level's own knobs
  common/             everything that does not depend on the level
  models/eye/         highest abstraction: slew and a reference comparison
  models/circuit/     lowest abstraction: the front end as it is built
```

## The levels

| | what it is | what it costs | what it is for |
|---|---|---|---|
| behavioral | `scala/resources/vsrc/*.v`, no analog at all | Verilator, seconds | RTL: word rates, bit order, reset |
| `models/eye` | finite driver slew, a `vref` comparison | Xcelium AMS, ~8 min for `phy_tb`, ~30 s for `training_tb` | training, bring-up sequences, full-stack MMIO tests |
| `models/circuit` | switched-capacitor sampler, driver segments, clock phase noise | Xcelium AMS, ~15 min for `phy_tb` | sizing, calibration ranges, jitter |

The behavioral tier is not in this directory. It lives next to the Chisel
blackboxes that select it (`includeDefaultModels`), models a lane as a shift
register, and drives its output as a logic level. Nothing about a code makes a
lane fail there, so it cannot be trained against; that is the gap `models/eye`
exists to close.

### `models/eye`

The highest abstraction that can still be trained against. Two things are
modelled and everything else is dropped:

- **A driver hands its output between rails over `EYE_TX_SLEW`.** With the
  receiver's termination on the far end that is a whole eye -- height from the
  divider the two impedances form, width from the ramp -- so where in the UI a
  lane is sampled decides whether it reads the bit or the edge.
- **A receiver compares the level on its bump against `vref`.** So the
  reference ladder code decides where in that eye the threshold sits, and a code
  outside the swing slices every UI the same way.

Those two are what `verilog/common/training_tb.sv` sweeps, and what the MMIO
training sequence in `scala/src/tilelink/Codegen.scala` sweeps over the same
pins from software.

What is dropped: the sampler that physically performs the comparison, the
offset it carries, the segment mismatch in the drivers, the phase noise of the
clocking, and any channel beyond a wire.

So the two axes of the eye do not come out the same shape. The **height** is
real and both its edges are: `verilog/common/training_tb.sv` measures 0.516 V of
it at the default codes, which is the supply divided by the driver's on
resistance against the far termination, and a reference code outside that range
kills the lane. The **width** comes out about one UI, because nothing at this
level closes it: a sample taken part way up an edge still resolves to the old
bit or the new one, so there is no band of sampling points that fails outright,
only the UI slip at each end of the run. Jitter and ISI are what narrow a real
eye and both are `models/circuit`'s business.

### `models/circuit`

The front end as it is built: the auto-zeroing switched-capacitor sampler out
of switches, capacitors and inverters; the drivers as counts of unit segments;
the delay line and the distribution network with their white and flicker phase
noise. Treat the current models as the floor of this level rather than the
finished article -- `bbpll.vams` and `s2d.vams` are here too, and neither is
instantiated by the PHY yet.

Run it with transient noise enabled in Spectre (`noisefmax` above the clock
frequency; see `xcelium/amscf.scs`) for anything the noise models are supposed
to answer.

## The contract

A level is a directory under `models/` that defines exactly these, with these
pins:

| cell | pins |
|---|---|
| `tx_tile_driver` | `din`, `ENP`, `ENN`, `ENP_EQ`, `ENN_EQ`, `dout`, `vddq`, `vss` |
| `pad_driver_cell` | `din`, `pu_ctl`, `pd_ctlb`, `en`, `enb`, `dout`, `vdd`, `vss` |
| `termination` | `vin`, `en`, `zctl`, `vss` |
| `rdac` | `out`, `sel`, `vdd`, `vss` |
| `rx_afe` | `vref`, `din`, `a_en`, `a_pc`, `b_en`, `b_pc`, `sel_a`, `dout`, `vdd`, `vss` |
| `dcdl` | `clk_in`, `dl_ctrl`, `clk_out`, with `delay_offset` and `delay_gain` |
| `clocking_distribution_model` | `clk_in`, `clk_out`, with `propagation_delay_mu` and `propagation_delay_sigma` |

Cells a level builds those out of are its own business and belong in its own
directory, as do the benches that only make sense against them --
`inv_selfbias_tb` at the circuit level, say, or the clocking benches under
`models/circuit/clocking_testbenches`. Nothing outside a level may instantiate
its internals. Two cells that read the same at both levels today (`rdac`,
`termination`) are still written out per level rather than shared, because what
separates the levels is exactly the detail those two have not grown yet.

`verilog/common/afe_tb.vams` holds the benches for the contract, and
`rs/src/verilog/afe.rs` runs every one of them at every level. A cell that
passes there can be swapped for another level's version of it without anything
upstream noticing. The same file lists the cells in `CONTRACT_CELLS` and checks
that every level declares all of them, so a level that is missing one says so
rather than failing to elaborate.

## Bumps are pins, not interface members

A SystemVerilog interface cannot hold an electrical net. A bump routed through
one is resolved to a logic net, so the analog model on each side of it gets a
connect module instead of a shared node -- the receiver is then driven by an
ideal rail-to-rail source and never sees the level the driver and the far
termination divide down to. Eye height would simply not exist.

So `txdata_tile`, `rxdata_tile`, `rxclk_tile`, `sb_driver_tile` and `phy` carry
their bumps as pins, and everything else on those tiles stays in the interface.
A bench wires two tiles together by handing the same `wire` to both, rather than
by assigning one interface member to another.

The blackbox shims (`tx_lane`, `rx_data_lane`, `rx_clock_lane`, `sb_driver`) are
the exception and cannot be fixed: their ports are `Bool()` on the Chisel side,
so a design emitted from Chisel digitizes its bumps at the tile boundary no
matter what. A full-stack AMS simulation therefore has an eye whose edge rate
comes from the connect modules configured in `xcelium/amscf.scs` (`tr`/`tf`) and
a rail-to-rail swing. Both training axes still work there -- which is what
`TrainMainbandTestDriver` in `scala/test/src/tilelink/TileLinkSpec.scala`
exercises -- but the level a lane slices against is the supply rather than the
divider, so `rxctl_<lane>_vrefSel` centres near the middle of the ladder rather
than near the bottom of it.

## Where the sampling point lands

`phy` offsets the forwarded clock by two clock distribution delays and a
quarter period, on the reasoning that the first cancels the distribution on
each side and the second puts the clock in the middle of the eye. Two
distribution delays are 400 ps and a UI is 62.5 ps, so the first part does not
cancel -- it leaves 25 ps over -- and the deserializer's own input delay moves
things again. Where the sampling edge actually lands is therefore left over
from delays that were never meant to add up, and it moves when the models
underneath do.

Against `models/circuit` it lands inside the eye; against `models/eye` it lands
on the edge, and the loopback reads back the wrong bits. Nothing is wrong with
either model -- an untrained lane has no reason to be centred, which is the
whole reason a lane has a delay line. `phy_tb` therefore walks that delay line
until it is sampling inside the eye before it checks anything, the same way
`training_tb` and the MMIO training sequence do, and prints the code it settled
on.

## Running

From `rs/`:

```bash
cargo test                              # every bench at every level
cargo test eye                          # the eye level only
cargo test verilog::training            # the training sweep
```

`verilog::phy::tests::circuit` is the long one: about 15 minutes against 20
lanes of switched-capacitor front ends, where the eye level does the same run in
about 8. Everything else finishes in seconds.

`Level` in `rs/src/verilog/mod.rs` is the list of levels; adding a directory
under `models/` and an entry to that enum is the whole of adding one.

From `scala/`, a full-stack simulation picks its level with the `amsLevel`
argument to `Utils.simulate`:

```scala
Utils.simulate(dut, Utils.writeXrunSimScript, workDir, amsLevel = Some(AmsLevel.Eye))
```

with `None` (the default) selecting the behavioral models out of
`scala/resources/vsrc`.

Xcelium needs a real Spectre ahead of Liberate's stub on `PATH`:

```bash
PATH=/tools/cadence/SPECTRE/SPECTRE251/bin:$PATH cargo test
```
