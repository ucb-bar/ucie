# Loopback bring-up testbenches

Testbenches that wire dies together and walk the link up from reset, one LTSM
state per test. Each one covers a different slice of the stack, so a
failure in the widest testbench can be located in a narrower one.

Every test is a cold start and climbs to the state it names. The tests are
independent, so the first failing test names the first thing the link cannot do.

Only die 0 takes the software trigger. Die 1 has to wake on the sideband clock
pattern die 0 transmits, which is the arrival order two chiplets actually see.
A testbench that starts both dies together would hide every bug in the remote
wake path.

## The testbenches

| Testbench | DUT | Driven by | Stages |
|---|---|---|---|
| `LogPhyStagedBringupTest` | two `LogicalPhy` | testbench pins | 9 |
| `MmplStagedBringupTest` | two `MultiModulePhy` | testbench pins | 17 per configuration, plus 3 of Link failure and 3 at one module |
| `MmplBypassTest` | a bypass-capable `MultiModulePhy` and two `LogicalPhy` partner dies, or two bypass-capable `MultiModulePhy` | testbench pins | 5 |
| `UcieDigitalStagedBringupTest` | two `ProtocolLayer` + `D2DAdapter` + `LogicalPhy` | testbench pins | 13 |
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

Stages 8 and 9 take the two degrade arcs of spec Figure 4-48, using the
harness's lane error injection -- corrupting a module's receive lane inside
MBTRAIN.LINKSPEED is the only way to make it report errors on a loopback where
every lane is perfect.

Stage 8 corrupts one module, which is fewer than half, so the resolution
disables it along with the other module of its half (spec 5.7.3.4.1 rule 2) and
the rest carry on. The surviving modules cannot fit the whole aggregate word in
one 8-UI interval any more, so this is also where the multi-beat datapath of
Figure 4-46 carries real data.

Stage 9 corrupts a majority, which at 4 GT/s takes the bandwidth comparison down
the width-degrade arc instead: every module width degrades and none is disabled,
including the module that found no errors of its own. Stage 10 then carries that
degraded link the rest of the way, to ACTIVE and through a multi-beat data
transfer -- the check that the link *settles* rather than ping-ponging between
LINKSPEED and REPAIR.

Stage 12 repeats Stage 8 with the failing module's sideband a few cycles slower
than its sibling's, through the harness's `sidebandRxDelayCycles`. Each die
resolves MBTRAIN.LINKSPEED once its own last module has reported, so the two
dies then resolve at different times, and the early die's directed response
reaches a module whose own die has yet to answer. The stage fails if that
response is dropped instead of kept.

Stage 13 injects the lane fault inside MBINIT.REPAIRMB instead, through the
harness's `repairMbLaneErrorInjection`, and in one direction of one module only.
Die 1's transmitter degrades away from the fault, and spec 4.5.3.3.6 Step 3 then
has to put both directions of that module, on both dies, on the one lane map.
The pair is then narrower than the rest, so the MMPL disables it (spec
4.7.1.2.1) and the other modules carry the data. The stage also depends on the
REPAIRMB point test reporting per-lane results at all, and on the second pass
comparing the upper lanes against their own lane IDs.

Stage 14 corrupts one module's valid lane in ACTIVE, through
`validErrorInjection`. The physical layer raises `pl_error`, stalls the adapter
and requests Retrain itself (spec 4.5.3.7.2). Both RDIs go to Retrain while both
adapters still request Active, and they stay there until `lp_state_req` goes NOP
to Active (spec 10.3.3.4). Data then has to flow in both directions again, which
fails if the module that saw the error drops its first slice on the way back
into ACTIVE.

Stages 15 and 16 stop one module pair training at all, through the harness's
`moduleFaultInjection`, and check that both dies come up on the rest (spec
4.7.1: "if any module failed to train, the MMPL must ensure that the
multi-module configuration degrades"). Stage 15 cuts the pair's sideband, so
both modules time out in SBINIT; Stage 16 holds one die's module in RESET with
its PLL unlocked, so only the other die's module times out, and that is how the
other die finds out. Either timeout arrives while the siblings wait on the
resolution in MBTRAIN.LINKSPEED. The stages fail if it reaches the one RDI state
machine and takes the link to LinkError.

Stage 17 raises `lp_linkerror` on one die, through the harness's
`linkErrorInjection`. Both RDIs go to LinkError, one on its own adapter's
signal and the other on the `{LinkMgmt.RDI.Req.LinkError}` it is sent, and
every module is held in TRAINERROR for as long as that lasts (spec 4.5.3.8).
Once `lp_linkerror` drops, each RDI leaves for Reset after its minimum
residency (spec 10.3.3.7), and software trains the link again from scratch.

Three stages at two modules, matching Module IDs, fail the whole link rather
than one module of it. With nothing left to degrade to, the link retries, or
escalates with no retry left, as a one-module link does. Stages 1 and 2 cut die
0's receive sideband on both modules, so they time out in SBINIT in the same
cycle: with a retry, the link comes back on both modules at full width, the RDI
never leaving Reset; without, die 0 raises `pl_trainerror` and LinkError. They
fail if each module counts the other as still training and is degraded around
it. Stage 3 fails module 1 while module 0 trains on, which degrades it, then
fails module 0 too. The retry restores module 1 and fails the other way round,
module 0 first, and module 1, the last to fail, must escalate. It fails if a
restored module starts with no training episode.

A one-module configuration runs three more stages. One is the one-sided fault
of Stage 13, but inside MBTRAIN.LINKSPEED, so the width changes in
MBTRAIN.REPAIR (spec 4.5.3.4.13 Step 2). A multi-module link never reaches
REPAIR with a clean module, because the MMPL width degrade halves every
module, so only one module exercises the rule there. The other two cut die 0's
receive sideband so that its training times out in SBINIT. With a retry left
(`retryTrainingAmt`), the module goes back through RESET and trains, and the
RDI never leaves Reset. With none, the failure escalates: `pl_trainerror` and
LinkError, the module held in TRAINERROR until the RDI has left LinkError, and
`pl_trainerror` dropping only then.

The testbenches stand in for an adapter by asking for Active once training is
done (`requestActive`, `trainThenActivate`): the RDI leaves Reset only on the
upper layer's NOP to Active (spec 10.3.3.1), never on its own.

Link training residency timeouts are shortened here through
`timeoutCyclesOverride`. The spec value is 8 ms, 6.4M cycles at 800 MHz with a
3.2M-cycle minimum RESET wait, which is far more than these checks need once
there are eight `LogicalPhy` instances in the simulation.

### LinkSpeedPhyRetrainTest

Not a ladder: each test builds the MMPL loopback, climbs to MBTRAIN.LINKSPEED
and takes the exit to PHYRETRAIN there (spec 4.5.3.4.12 Steps 3 to 5) with the
two ends of a module, or the modules of a link, out of step. One module's
transmitter is held back through `mainbandStallInjection`, so its Step 2 point
test is still open when the exit arrives, and the test fails if the link can
leave only by waiting that out.

The rest check Step 5's {exit to PHY retrain resp}. Every module of a die that
leaves LINKSPEED sends one on its own sideband, siblings moved by the MMPL's
directive included. Those tests count what each module sends through
`exitToPhyRetrainProbe`. `specLiteralSenderDie` builds one die as
`SpecLiteralMultiModulePhy`, whose modules relay nothing to each other, so its
siblings can leave only on the resp their partners send them.

### MmplBypassTest

A two-module `MultiModulePhy` built with `MmplParams.bypassable`, which can run
its modules as one multi-module link or, with the MMPL bypassed, as independent
single-module links (spec 4.7.2, Figure 4-49). `MmplBypassHarness` sets the
bypass bit and wires each module of die 0 to a different single-module die, a
bare `LogicalPhy`, so each module is a link to a die of its own. Each link has
an adapter at either end, on die 0's per-module RDI (`moduleRdi`) and on the
partner's RDI. Only die 0's modules take a training trigger, one per link.

Stage 1 trains both links to Active and checks each is a one-module link: x16
`pl_lnk_cfg`, each end seeing Module ID 0 from the other (a bypassed module is
M0 of its own link, spec 5.7.3.4), and die 0's aggregate RDI left in Reset. It
then clears the bypass bit with both links up and checks that nothing changes.
The mode is only taken while every module is in RESET and every RDI in Reset.
Stage 2 sends tagged words over both links at once, in both directions. The
tags name the link and the end, so a word delivered on the wrong link fails.

Stages 3 and 4 check the links are independent. In Stage 3, only module 0 is
triggered. Its link comes up and carries data while module 1 and its partner
stay in RESET, which fails if the sibling is pulled out of RESET as it would be
on a multi-module link. Module 1 is then triggered, and link 0 has to stay
Active, and carry a word, while link 1 trains beside it. In Stage 4, link 1
retrains at its adapter's request while link 0 stays Active and carries data.

Stage 5 runs the same bypass-capable hardware with the bypass clear, against a
matching die (`MmplLoopbackHarness`). It checks that the two modules are one
link at x32, that the modules still advertise their index as Module ID, and
that a tagged word crosses the aggregate RDI under the Figure 4-44 pairing.

### UcieDigitalStagedBringupTest

The full stack built by hand, two of everything. A real `D2DAdapter` drives the
RDI here, so the clock and stall handshakes and the cfg credits are hardware
rather than testbench pokes.

PHY training is one stage rather than nine, because the LogPhy testbench already
covers it. What this adds is everything above the RDI: the ADV_CAP exchange,
protocol negotiation, the FDI handshakes and protocol beats crossing the link.

Stage 12 retrains the link at die 0's protocol layer's request and checks it
comes back to FDI Active and carries data. Out of Retrain every interface's
upper layer presents NOP then Active (spec 10.3.3.4): the protocol layer on
FDI, the adapter on RDI. The physical layers run their Active Entry handshake
once they have retrained, and the adapters then run theirs (spec 10.2.8).

Stage 13 resets the link from Active at die 0's protocol layer's request. The
adapter asks RDI for LinkReset once its own state machine is there (spec 3.5),
so the physical layer's `pl_stallreq` (spec 10.3.2) reaches an adapter that has
left Active. The stage fails if that stall goes unanswered and neither RDI
leaves Active. Out of LinkReset, Active takes each RDI to Reset (Table 10-4),
and the link trains again and carries data.

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
./mill test.testOnly edu.berkeley.cs.uciedigital.loopback.LinkSpeedPhyRetrainTest
./mill test.testOnly edu.berkeley.cs.uciedigital.loopback.MmplBypassTest
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
