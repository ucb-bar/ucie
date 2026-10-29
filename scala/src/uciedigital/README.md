# uciedigital

The UCIe digital stack. This document says what is here, what it targets, and
where to attach the features that are not built yet.

## What this implementation targets

**UCIe Rev 3.0 v1.0, Standard Package, Streaming protocol, Raw Format
(Format 1).** Single stack, single module, x16 with degrade to x8 and x4. FDI
and RDI carry `nBytes = 64` and `ncWidth = 32`.

Not built, deliberately: PCIe and CXL, Management Transport, ARB/MUX and CXL
vLSMs, flit formats 2 through 6 and everything riding on them (headers, CRC,
retry, ack/nak, `pl_flit_cancel`, `lp_corrupt_crc`), power management, retimers,
multi-module links, and Advanced Package repair.

Raw Format is what makes that scope coherent. Section 2.4.1 makes Protocol Layer
interoperability vendor defined and section 3.3.1 says Raw Format is only for
cases where Adapter retry is not required, so the payload is opaque to
everything below the Protocol Layer and end-to-end flow control belongs to the
chip.

Per-requirement conformance lives in `scala/docs/vplan/`, not here. That plan
carries the spec citation, the observable, and the implementation status for
every requirement in scope, plus an aggregated gap list. Do not restate it.

## Architecture

```
                chip
                 |  mainbandTx (Decoupled)   mainbandRx (Valid)
                 v
   +-------------------------------+
   |        protocollayer          |   sec 10.2, sec 10.3
   +-------------------------------+
                 |  FDI  (fdi/)
                 v
   +-------------------------------+
   |         d2dadapter            |   ch3
   +-------------------------------+
                 |  RDI  (rdi/)
                 v
   +-------------------------------+
   |           logphy              |   ch4
   +-------------------------------+
                 |  PhyBridge
                 v
            analog PHY

   sideband/   the sideband node, switch, and serdes, and each layer's channel
   regs/       the UCIe register block
   top/        UcieDigitalTop and the register bridge that joins the blocks
```

The mainband RX path has no backpressure. Section 10.2.4 gives the FDI receiver
no way to stall the Adapter and section 10.2.2 puts the Protocol Layer in the
Adapter's clock domain, so `mainbandRx` is `Valid` and the chip sinks
unconditionally. Any clock crossing above the Protocol Layer has to absorb a
full credit window on its own. `UcieTLParams.queueParams` carries that
constraint.

## File structure

One package per top-level directory. Subdirectories organize files; they do not
create packages. So `rdi/physical/RDIController.scala` declares
`package edu.berkeley.cs.uciedigital.rdi`, and moving a file inside a package
changes no imports.

```
uciedigital/
  README.md          this file
  protocollayer/     the Protocol Layer top and its datapath bundles
  fdi/               FDI: the bundle and encodings, and both sides' logic
    adapter/           the Adapter side (FDI's lower layer)
    protocol/          the Protocol Layer side (FDI's upper layer)
  d2dadapter/        ch3: the Adapter LSM, init, mainband, sideband, mailbox
  rdi/               RDI: the bundle and encodings, and both sides' logic
    physical/          the Physical Layer side (RDI's lower layer)
    adapter/           the Adapter side (RDI's upper layer)
  logphy/            ch4: LogicalPhy.scala, link training, the mainband
    modules/           mainband/ patterns/ linktraining/ (d2c/) trainer/
  phybridge/         the analog boundary: the per-PHY adapter and models
  sideband/          ch7: node, switch, serdes, queues, and each layer's channel
  regs/              ch9: the register block; bundles/ and blocks/
  top/               UcieDigitalTop and UcieRegBridge
  common/            what every layer shares: SpeedMode, LinkWidth, the LTSM
                     states, UcieTiming, the PHY knob table, and the
                     ReqAck and Stall handshakes used on both interfaces
  utils/             generic hardware helpers
  elaboration/       one entry point file per area, plus the firtool options
```

The directories follow the stack. Each interface directory holds everything
Chapter 10 defines for that interface: the bundle, its encodings, and both
sides' state machines and handshakes. Each layer directory holds only its own
chapter, and its top module instantiates its side of each interface, as each
layer instantiates its sideband channel from `sideband/`.

Within a package: `bundles/` for IO bundles, `objects/` for `ChiselEnum`
encodings, parameters and spec tables, `modules/` for submodules, and the
package's top module in the package directory itself. An IO bundle that only
one module uses lives in that module's file.

The package dependencies run one way: `common` and `utils` at the bottom, then
`sideband`, `rdi`, `fdi`, and the layers on top. Keep it that way. A module in
`rdi/` or `fdi/` must not import a layer's package.

## Rules for adding RTL

- A state machine earns a module. Straight-line datapath does not. An FSM has
  state, transitions, and a contract worth reading on its own, and it is where a
  future feature attaches. The mainband TX and RX datapaths are inlined in
  `ProtocolLayer.scala` behind banner comments because neither is one.
- No helper function or parameter that only one call site uses.
- Every line wraps at 80 columns, code and comments alike. Reformat only the
  lines you touch. `./mill mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll`
  reports the state; `reformatAll` rewrites the whole repo, so prefer fixing
  your own lines by hand.
- Comments say why something exists or why an unusual choice was made, not what
  the code plainly does. One description header per file, then comment
  sparingly. At most three lines each. Cite a spec section as `sec 10.2.7` or a
  table as `Table 10-4`, never a line number, which goes stale silently.
- Assertions and coverpoints are native Chisel inside `block(Verification)`.
  There is no `bind`-in-SV flow and none is planned. An assertion whose
  antecedent is never covered is not coverage, so pair it with a `cover`.
- Do not write a layer's status into the register block's own error bits.
  ch9 section 9.5.3.3 makes that feed one way, and writing back closes a
  combinational loop through `UcieRegBridge` into the Adapter.

## Agentic development notes

- The spec is the source of truth, and the extracted text is in
  `scala/docs/spec/`. Where the vplan, a comment, or a plan disagrees with it,
  the spec wins. Quote the sentence you are relying on.
- Check a structural claim against the emitted Verilog in `generatedVerilog/`,
  not against the Chisel. Register counts, reset fan-out, and combinational
  paths to a pin all read differently after lowering.
- The staged bring-up ladders catch what unit tests do not. A change that passes
  `ProtocolLayerTest` can still hang both dies in
  `UcieDigitalStagedBringupTest`, which is how the `lp_state_req` level versus
  transition defect was found.
- `UcieDigitalLoopbackHarness` has `exposeDataPath`, false by default. A ladder
  rung that exercises the mainband datapath without setting it passes vacuously,
  because the 512-bit chain folds away.

## Elaborating

Each block has an entry point under `elaboration/`, and they share the firtool
option sets in `elaboration/Settings.scala`.

```
cd scala
./mill runMain edu.berkeley.cs.uciedigital.elaboration.MainProtocolLayer
```

Output lands in `generatedVerilog/<area>/`, with assertions and coverpoints
under `generatedVerilog/<area>/verification/{assert,cover}/`. Every entry point
is in `elaboration/`; add new ones there, not next to the RTL.

## Where future features attach

**Dynamic clock gating.** Three tie-offs, all of them currently spec-literal
and correct:

- The clock handshakes are `fdiClockRequester` and `rdiClockResponder` in
  `D2DAdapter`, and `clkHandshake` in `ProtocolStateController`. Each has its
  `ctrl` inputs tied off, with `clocksUngatedAndStable` true on a responder.
  Drive them from a real clock control unit.
- The wake handshakes are `fdiWakeResponder` and `rdiWakeRequester` in
  `D2DAdapter`, and `wakeHandshake` in `ProtocolStateController`. A requester
  has `startHandshake` tied high, which parks it in `sACTIVE_HOLD` so
  `lp_wake_req` reads the constant 1 that Tables 10-1 and 10-3 permit. Drive
  `startHandshake` from a real request instead.
- `FdiRxActiveResponder` has `receiverReady` tied true because the receiver is
  one register stage. A receiver that needs to defer drives it.

**Do not gate `lp_cfg_vld` on the wake handshake.** Section 10.2.3.1 rule 6 asks
for it when performing the handshake, but the sideband serializer output is
`Valid` with no backpressure across four consecutive beats, so a mid-packet gate
drops a phase and silently corrupts the packet against section 7.1.4. If it is
ever closed it has to be a level gate at packet start only.

**Power management.** `ProtocolStateRequestSM` is where L1 and L2 attach: new
rows in the Table 10-4 legality decode, and the section 10.2.9 request hold that
is already there for the PMNAK arm. Nothing else in the layer changes. The
Adapter side needs the PMNAK responder, which `RDIStateMachine` already
implements for RDI.

**A protocol-layer sideband interface.** `ProtocolLayer` ties off
`sidebandChannel.io.layer`. Table 7-2 note a says no requests are permitted from
the Adapter to the Protocol Layer over FDI sideband, so only the request
direction needs building. When it is, `ProtocolSidebandChannel` needs
`routeRegAccessToCurrent = true` so completions route by tag rather than by a
Reserved `dstid`.
