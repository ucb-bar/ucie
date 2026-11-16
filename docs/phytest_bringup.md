# PhyTest bring-up guide

This guide covers bringing up a UCIe link under PhyTest, the IP's built-in link tester, on silicon.
It's generic to the IP. Chip-specific details live with the chip: base addresses, which bumps carry
which clock, and how software reaches the registers. For Iris, see `docs/ucie_bringup.md` in the
Iris repository.

**Conventions**
- File paths are relative to the root of this repository. Code citations are `file:line`.
- Register names are the ones the generated C header uses (`UCIE_...`). `GenUcieHeader` emits the
  header from `scala/src/tilelink/Codegen.scala`. Regenerate it from the chip's own `UcieTLParams`
  whenever the IP or the parameters change, because the per-lane arrays are sized from them.
- Offsets are relative to `base`, the link's `UcieTLParams.address`. The generated helpers all take
  `base` as their first argument.
- "Verified" statements come from the RTL and the behavioral models. Bench numbers are not known yet,
  and statements that depend on analog behavior are marked as such.

**Figures** (in `docs/figures/`)
- `top_level_clocking_doc.png`: every clock domain, its async FIFOs, and the sideband controllers.
- `clk_manager_doc.png`: the clocking tile, showing which fields are applied and which take effect
  immediately.
- `clk_dividers_doc_chains.png`: the PHY test and TX sideband divider chains.
- `clk_dividers_doc_tx.png`: the TX divider and phase shifter.
- `lane_clock_distribution.png`: the lane clock trees, the delay lines, and the RX clock select.

---

## 1. Clocking structure

![Top-level clocking](figures/top_level_clocking_doc.png)

### 1.1 Clocks and what they clock

| Clock | Source and range | Selected by | Clocks | Code |
|---|---|---|---|---|
| **Chip clock** | The SoC clock on `UcieTL.digitalClockNode`. | Fixed | `UcieClkRegs`, the register port (crossbar, FIFO fixer, source shrinker, the bus side of both register crossings), the TL path (framing, credits, A/D buffers), the sideband TL link's body, the enqueue end of `txTlFifo`/`txAQ`, the dequeue end of `rxTlFifo`/`rxAQ`. | `scala/src/tilelink/TileLink.scala:1924-1931,1989-2021,2364-2785` |
| **PHY test clock** `PhyTestClk` | The `digitalBypassClk` pin (reset default; the figure gives ≤ 1.1 GHz), or PLL8, PLL12 or the analog bypass pin divided by 1, 2, 4, 6, 8, 12 or 15. Nominally ~1 GHz. | `phyTestClkBypassEn`, `phyTestClkSrcSel`, `phyTestClkDiv` (applied); gate `phyTestClkEn` (immediate) | `UcieTLRegs` (every PhyTest, lane-control and debug register), `PhyTest` (FSMs, SRAMs, LFSRs, counters), every RX AFE controller (`RxAfeCtl`, clock lanes included), the PhyTest ends of the mainband test queues and of PhyTest's own lane queues, the sideband tester's register side. | `scala/src/phy/Phy.scala:249-258,406,555-561`; `TileLink.scala:1990,2050-2062` |
| **TX sideband clock** `SbClk` | The `sidebandBypassClk` pin (reset default, 800 MHz), or PLL8, PLL12 or the analog bypass pin divided by 1, 5, 10 or 15 (800 MHz is PLL8/10 or PLL12/15). | `sbClkBypassEn`, `sbClkSrcSel`, `sbClkDiv` (applied); gate `sbClkEn` (immediate) | `UcieController` (the UCIe digital stack and its spec registers), every sideband serializer (PhyTest's tester, the SB TL link, the controller), the controller's mainband queue ends. | `Phy.scala:260-263,407-414`; `TileLink.scala:1991,2171-2177,2203-2210,2322-2356` |
| **UCIe main clock** | PLL8 (8 GHz), PLL12 (12 GHz), the PLL8 X2 clock (16 GHz), or the analog bypass pin `bypassClk` (≤ 16 GHz; reset default). | `mainClkSel` (applied); gate `clkEn` (immediate) | The TX divider only. | `scala/src/phy/macros/clocking/Common.scala:83-121,168-177` |
| **TX data clock** `TxClk` | Main clock ÷ 1, 2 or 4. | `txClkDiv` (applied) | Data, valid and track TX lanes; PhyTest's TX debug lane, loopback TX lane and loopback RX lane; the global TX ÷16. | `ucie_clk_dist_network.sv`; `figures/lane_clock_distribution.png` |
| **TX quadrature clock** `TxClkQ` | `TxClk`, advanced by `txClkPhase` half-cycles of the main clock, then through the global delay line (64 thermometer taps, ~1.086 ps each in the model). | `txClkPhase`, `clkPhaseSel` (both immediate) | The two forwarded-clock TX lanes; optionally the RX clock tree (`rxClkFromTxQ`). | `Common.scala:80-81,114-121`; `resources/vsrc/clocking_tile.v` (`PHASE_TAP_PS`) |
| **TX divided clock** `txDivClk` | `TxClk` ÷ 16, inverted (`ucie_clk_div4`, a ripple ÷16). The TX tiles load on the rising edge of their own ÷16, in step with the global one, so the queue presents each word half a word ahead of the load. | Follows `TxClk` | Hand-off of each TX word to the tiles; the PHY end of every TX queue; the TX underflow flags. | `Phy.scala:458-481` |
| **RX clock** | The partner's forwarded clock on `rxClkP` through the RX clock lane gate `rxClkEn`, or this die's `TxClkQ` when `rxClkFromTxQ` = 1. | `rxClkEn`, `rxClkFromTxQ` (both immediate) | The RX clock tree: every RX data, valid and track tile, plus the global RX ÷16. | `Phy.scala:561-579`; `figures/lane_clock_distribution.png` |
| **RX divided clock** `rxDivClk` | RX clock ÷ 16, **not** inverted. The RX tiles put their word out on the falling edge of their own ÷16, in step with the global one, so the queue enqueues half a word after the word appears. | Follows the RX clock | Hand-off from the RX tiles; the PHY end of every RX queue; the RX overflow flags. | `Phy.scala:482-490` |
| **RX sideband clock** | The partner's `sbTxClk` on the `sbRxClk` bump. | n/a | Every sideband deserializer (falling edge) and its RX queue's enqueue end. | `scala/src/phy/SidebandSerial.scala` (RX section) |
| **Reference clock** `refClk` | 100 MHz bump. | n/a | Both PLLs (×80, ×120). Needed only when a PLL is used. | `Common.scala:179-180`; `clocking_tile.v` |

Two properties hold by construction:
- Nothing done to the main clock reaches the PHY test or TX sideband clock. They divide a PLL or the
  bypass pin, not the main clock (`Common.scala:123-131`).
- The RX AFEs are sequenced on the PHY test clock, so it has to be running whenever any lane
  receives, even with the UCIe digital stack in control (`Phy.scala:249-258,555-561`).

### 1.2 The clocking tile

![Clocking tile](figures/clk_manager_doc.png)

**Gates.** Each output has a glitch-free gate after its mux: `clkEn` (main clock), `phyTestClkEn`
and `sbClkEn`. The gate's enable is latched while the gated clock is low (`clocking_tile.v`, the
`main_en`, `phytest_en` and `sb_en` latches). The muxes in front of the gates are **not**
glitch-free (`Common.scala:142-147`). Every source change has to happen with the gate shut, and the
apply sequencer in section 1.4 does that.

**Divider codes** (`Common.scala:83-88,109-158`):

| Field | Codes |
|---|---|
| `mainClkSel` | 0 PLL8, 1 PLL12, 2 X2 (16 GHz; needs PLL8), 3 analog bypass |
| `txClkDiv` | 0 ÷1, 1 ÷2, 2 ÷4, 3 = ÷4 |
| `txClkPhase` | 0 … 2·div − 1, in half-cycles of the main clock. At ÷1 only 0 and 180° exist. |
| `phyTestClkSrcSel`, `sbClkSrcSel` | 0 PLL8, 1 PLL12, 2 analog bypass, 3 = analog bypass |
| `phyTestClkDiv` | 0 ÷1, 1 ÷2, 2 ÷4, 3 ÷6, 4 ÷8, 5 ÷12, 6 ÷15, 7 = ÷15 |
| `sbClkDiv` | 0 ÷1, 1 ÷5, 2 ÷10, 3 ÷15 |

The odd ratios pass through a duty-cycle corrector (`clk_dividers_doc_chains.png`). A divider is held
at zero while its bypass is selected, so switching onto it starts a fresh period.

**Keep the main clock fast; divide for low rates.** A coarse phase step is half a main-clock
period. It fits inside the ~69 ps global delay line only for main clocks of about 7.2 GHz and up.
Below that, a sweep leaves gaps (`Common.scala:114-121`; the header of `clocking_tile.v`). So a low
line rate should come from `txClkDiv` on an 8 GHz or faster main clock, not from a slow bypass clock.

### 1.3 Line rates, and the rule for the PHY test clock

A TX tile is a 16:1 DDR serializer: 32 bits per lane per `txDivClk` cycle, with
`txDivClk` = `TxClk`/16 (`scala/src/phy/macros/Tx.scala`, `SerdesRatio`; `Phy.scala:473-476`).

| Main clock | `txClkDiv` | `TxClk` | Lane rate | UI | `txDivClk` (packet clock) |
|---|---|---|---|---|---|
| 8 GHz (PLL8 or bypass) | ÷1 | 8 GHz | 16 GT/s | 62.5 ps | 500 MHz |
| | ÷2 | 4 GHz | 8 GT/s | 125 ps | 250 MHz |
| | ÷4 | 2 GHz | 4 GT/s | 250 ps | 125 MHz |
| 12 GHz (PLL12) | ÷1 / ÷2 / ÷4 | 12 / 6 / 3 GHz | 24 / 12 / 6 GT/s | | 750 / 375 / 187.5 MHz |
| 16 GHz (X2) | ÷1 / ÷2 / ÷4 | 16 / 8 / 4 GHz | 32 / 16 / 8 GT/s | | 1000 / 500 / 250 MHz |

**The PHY test clock must be faster than both packet clocks**: this die's `txDivClk` and the
partner's, which arrives as this die's `rxDivClk`.
- During a run PhyTest feeds its TX queue one data word per PHY test clock, and it drains its RX
  queue one word per PHY test clock (`scala/src/phytest/PhyTest.scala:1305-1306,1545`). Between runs
  and bursts it queues nothing.
- The PHY end puts a word on the lanes every divided-clock cycle: the next queued one, or between
  them one of `TxClkGater`'s own (section 8.1). Its RX queue takes a word every cycle and can't be
  held off (`TileLink.scala:2122-2125,2171-2187`).

If the PHY test clock is slower, words drop silently and read as bit errors. Two sticky flags record
it:
- `UCIE_TX_TEST_FIFO_UNDERFLOW`: the TX queue ran dry partway through a run, where the last word
  said more of the run followed.
- `UCIE_RX_TEST_FIFO_OVERFLOW`: the RX queue overflowed.

Both are cleared by the datapath reset (`TileLink.scala:205-211,2189-2201`).

A slow PHY test clock shows up on the die whose TX it starves. Where the next data word isn't
queued in time, `TxClkGater` sends an idle word instead (`TileLink.scala:2683-2746`; `scala/src/phytest/TxClkGater.scala`): with the
clock running that is an extra idle word in the middle of the run, and with it gated the burst
splits in two, each with its postamble. On a looped-back link that reads as
`UCIE_TX_TEST_FIFO_UNDERFLOW` = 1 and `UCIE_RX_TEST_FIFO_OVERFLOW` = 0; Iris's
`software/phytest-clocks.c` checks exactly that. The RX flag sets only when a partner with a faster
PHY test clock sends to a die with a slower one.

Equal nominal rates are safe only when both clocks come off one source. From two unlocked
generators, a 32-entry queue drifts into underflow or overflow in under a millisecond at ~100 ppm
(inference). At 32 GT/s the packet clock is 1 GHz, so a 1 GHz PHY test clock has no margin there.

PHY test clock choices off the PLLs:

| Source | ÷6 | ÷8 | ÷12 | ÷15 |
|---|---|---|---|---|
| PLL8 | 1.33 GHz | **1 GHz** (the preset `phyTestClkDiv` = 4) | 667 MHz | 533 MHz |
| PLL12 | 2 GHz | 1.5 GHz | **1 GHz** | **800 MHz** |

Only use rates the PHY test domain was closed at. This guide doesn't know that number; the
domain's nominal is ~1 GHz (`Phy.scala:249`).

### 1.4 How a clock setting reaches the tile

`UcieClkRegs` runs on the chip clock and the chip reset, so its registers stay writable when every
PHY clock is stopped (`TileLink.scala:246-263`).

**Applied fields.** These are shadow registers. Nothing reaches the tile until
`UCIE_CLK_CFG_APPLY` (`TileLink.scala:546-580,684-813`):
- `mainClkSel`, `txClkDiv`
- `pll8En`, `pll12En`, `x2En`
- `phyTestClkSrcSel`, `phyTestClkDiv`, `phyTestClkBypassEn`
- `sbClkSrcSel`, `sbClkDiv`, `sbClkBypassEn`

An apply runs these steps:
1. Shut the main-clock gate always. Shut the PHY test or sideband gate only when this apply changes
   something under it and `clkCfgGatePhyTest`/`clkCfgGateSb` allows it (both reset to 1).
2. Wait `gateDwell` = 4 chip cycles.
3. Copy the shadows into the live registers.
4. Wait for the ungate condition.
5. Reopen the gates.
6. Wait 4 more cycles, then clear `UCIE_CLK_CFG_BUSY`.

**Ungate condition** (`UCIE_CLK_CFG_UNGATE_SRC`, `TileLink.scala:996-1019`):

| Value | Condition |
|---|---|
| 0, `pllLock` (reset) | Lock from every PLL a reopened clock now divides. The bypass pin counts as locked. |
| 1, `mmio` | Wait for `UCIE_CLK_CFG_UNGATE_REQ`. |
| 2, `delay` | `UCIE_CLK_CFG_UNGATE_DELAY` chip cycles; reset value 1024. |

Writing `UCIE_CLK_CFG_UNGATE_REQ` ends any wait, whatever the source (`TileLink.scala:617-621`).

**PLL keep-alive.** A PLL stays on while the PHY test or sideband divider takes it, whatever its
enable says (`TileLink.scala:841-853`).

**Immediate fields.** These take effect when written: `clkEn`, `rxClkEn`, `phyTestClkEn`, `sbClkEn`,
`txClkPhase`, `clkPhaseSel`, `rxClkFromTxQ`, `txDividerRst`, `rxDividerRst` and `ucieRst`
(`TileLink.scala:897-921`).
- The two delay controls, `txClkPhase` and `clkPhaseSel`, act on running clocks. Change them only
  with `clkEn` = 0.
- `rxClkFromTxQ` switches a plain mux on two running clocks (`Phy.scala:575-579`). Change it only
  with `clkEn` = 0 and `rxClkEn` = 0, then realign the RX dividers (section 2.4).

**Manual mode.** `UCIE_CLK_MANUAL_EN` = 1 hands every output to its own register, with no apply,
no keep-alive and no `ucieRst` on the dividers. Use it to re-sequence by hand. Entry and exit move
no output (`TileLink.scala:514-540,858-895`).

**Rate table.** `UCIE_RATE_CFG` + 8·rate, with `freqSelAutoEn`, is for the UCIe digital stack's
training (`TileLink.scala:375-392,726-748`). PhyTest doesn't use it. Leave `freqSelAutoEn` = 0. An
all-zero entry selects PLL8 with no PLL on.

### 1.5 Clock domain crossings

| Crossing | Mechanism | Notes | Code |
|---|---|---|---|
| Bus → `UcieTLRegs` (PHY test clock) and bus → `UcieController` (sideband clock) | One-entry `AsyncQueue` TL crossing, `safe` | The request isn't accepted until the destination domain is clocked and out of reset. See section 3. | `TileLink.scala:2007-2015` |
| `UcieTLRegs` → `PhyTest` | **None: same clock.** | Strobes such as `txExecute`, `txFsmRst`, `rxFsmRst`, `txInjectError` and `trackCntRst` are one-cycle pulses made in the PHY test domain and consumed there. The FSM and datapath resets are stretched to 8 cycles. | `TileLink.scala:1382-1512` |
| PHY test clock ↔ `txDivClk`/`rxDivClk`, mainband data | `AsyncQueue`, depth 32 | One queue per controller per direction; only the selected controller's TX words reach the lanes. | `TileLink.scala:2108-2187` |
| PHY test clock → divided clocks, datapath resets | Reset synchronizer: asynchronous assert, release after 3 divided-clock edges (`ucie_rst_sync.v`) | Release needs the divided clock to be running. The strobe leaves `UcieTLRegs` from a flop (`TileLink.scala:1389-1402,1427-1431,1442-1443`), so no glitch out of the write decode or the stretch counter reaches the asynchronous clear. | `Phy.scala:477-490`; `PhyTest.scala:848-851,1012-1015` |
| Divided clocks → PHY test clock, sticky flags | Two flops | Levels, so nothing is lost. | `TileLink.scala:1351-1358`; `PhyTest.scala:884,1051-1052` |
| `UcieClkRegs` → clocking tile | Registered levels; source changes only under a shut gate (section 1.4) | | `TileLink.scala:897-921` |
| `UcieClkRegs` → dividers | Asynchronous reset off a flop | Release only with that direction's clock stopped. | `TileLink.scala:649-675` |
| `UcieClkRegs` → every UCIe domain | `ucieRst` into a reset synchronizer per domain | | `Phy.scala:411-418,477-490`; `TileLink.scala:2037-2046` |
| PLL lock → chip clock; `freqSel` → chip clock; clock status → sideband clock | Two flops; `freqSel` must also hold for 3 cycles; lock must hold for 4 | | `TileLink.scala:399-417,738-747,2261-2288` |
| `UcieTLRegs` → lanes and divided-clock datapaths, quasi-static controls | **Not synchronized** | Covers `txctl`/`rxctl` (tile codes, shufflers, `sampleNegedge`, vref, termination, `Dctrl`), `controllerSel`/`mainbandMode`/`sidebandMode`, `txClkDirect` with `txClkP`/`txClkN`, `rxAlign_*`, the gating settings `TxClkGater` reads (`txClkPreamble`, `txClkPostamble`, `txIdlePackets`, `txClkParkAlternate`), and the debug muxes. Change them with the lanes quiet, then reset the datapath. Change delay codes only with `clkEn` = 0. The gate itself (`txClkGate`, or the controller's `mbTxClkGate`) crosses through a two-flop synchronizer and can change at any time. | `TileLink.scala:1453-1482,2099-2118,2683-2753`; `Phy.scala:516-551,589-666` |

---

## 2. Reset structure

### 2.1 The resets

| Reset | Asserted by | Released | Resets | Code |
|---|---|---|---|---|
| Chip reset | The SoC | The SoC | `UcieClkRegs` (with `ucieRst` = 1), the chip-clock logic, the bus side of the crossings. **It reaches nothing else in the block.** | `TileLink.scala:623-647,2037-2041` |
| `UCIE_UCIE_RST` | Reset value **1**, set by the chip reset asynchronously (so the UCIe domains are held from power-on, before the chip clock runs), or software | Software writes 0 | Every UCIe domain through its own synchronizer: the PHY test domain (`UcieTLRegs`, PhyTest, AFE controllers), the sideband domain (controller and its registers), and both datapaths. In FSM mode it also holds both divider resets. | `TileLink.scala:644-646,669-675,2042`; `Phy.scala:411-418,477-490` |
| PHY test and sideband domain resets | `ucieRst`, asynchronously | 3 edges of that domain's clock after `ucieRst` = 0 | As above. The registers there reset **synchronously**, so they only reset while their clock runs. | `Phy.scala:411-418` |
| `UCIE_TX_DIVIDER_RST`, `UCIE_RX_DIVIDER_RST` | Software level (or `ucieRst`) | Software level | TX: the global TX ÷16, every TX tile, PhyTest's TX debug and loopback TX lanes. RX: the global RX ÷16, every RX data tile, PhyTest's loopback RX lane. | `TileLink.scala:649-675,2065-2070`; `Phy.scala:475,485,534,614`; `PhyTest.scala:841,1005` |
| `UCIE_TX_DATAPATH_RST`, `UCIE_RX_DATAPATH_RST` | Software strobe (8 PHY test cycles) | Synchronized to `txDivClk`/`rxDivClk` | The serdes hand-off registers, the PHY end of every queue in that direction (the `safe` queues also reset the far end's pointers), the underflow/overflow flags, and PhyTest's lane queues. **Not** a divider, **not** an FSM. | `TileLink.scala:224-228,1389-1402,1430-1431,1442-1443`; `Phy.scala:477-490` |
| `UCIE_TX_FSM_RST` | Software strobe | Synchronous | TX FSM back to idle; `txPacketsSent` to 0; LFSRs reseeded from `txLfsrSeed`; error injection disarmed; and burst state. The clock gating is `TxClkGater`'s, at the PHY end, which the TX datapath reset resets to parked low (gated) or running; nothing else touches the level it last parked at, so a run starts where the last one parked. Also held while `mainbandMode` = `tl`. | `PhyTest.scala:1055-1077,1320-1327` |
| `UCIE_RX_FSM_RST` | Software strobe | Synchronous | RX FSM, all RX counters and error counts, signature, framing, capture state, self-sync history; LFSRs reseeded from `rxLfsrSeed`. Also held while `mainbandMode` = `tl` unless `rxMonitor` = 1. | `PhyTest.scala:1085-1127,1157-1162,1591-1663,1718-1720,1761-1766` |
| `UCIE_DEBUG_TX_FSM_RST`, `UCIE_TRACK_CNT_RST`, `UCIE_SB_RX_RST` | Software strobe | Synchronous | The TX debug lane FSM; the track counters; the sideband tester's receiver. | `PhyTest.scala:893-911,2005`; `scala/src/phytest/SidebandTest.scala` |

`scala/test/src/phytest/ResetSchemeSpec.scala` pins down which state belongs to which reset.

### 2.2 Reset values that matter for bring-up

`UcieClkRegs` (`TileLink.scala:346-373,479-512,611-668`):

| Register | Reset | Meaning |
|---|---|---|
| `UCIE_UCIE_RST` | 1 | The block is held. PhyTest and controller registers **do not answer** (section 3). |
| `UCIE_MAIN_CLK_SEL` / `UCIE_TX_CLK_DIV` / `UCIE_TX_CLK_PHASE` | 3 / 0 / 0 | Main clock from the `bypassClk` pin, ÷1, no coarse phase. |
| `UCIE_CLK_PHASE_SEL` | all 64 taps | The longest global delay, ~69.5 ps. At ÷1 that's ~1.1 UI. See step 5. |
| `UCIE_CLK_EN` / `UCIE_RX_CLK_EN` | 0 / 0 | Main clock and RX clock off, so the dividers leave reset with no clock. |
| `UCIE_PHY_TEST_CLK_BYPASS_EN` / `_SRC_SEL` / `_DIV` / `_EN` | 1 / 0 / 4 / 1 | PHY test clock from the `digitalBypassClk` pin. The divider is preset to PLL8 ÷8. |
| `UCIE_SB_CLK_BYPASS_EN` / `_SRC_SEL` / `_DIV` / `_EN` | 1 / 0 / 2 / 1 | Sideband clock from the `sidebandBypassClk` pin. The divider is preset to PLL8 ÷10. |
| `UCIE_PLL8_EN` / `UCIE_PLL12_EN` / `UCIE_X2_EN` | 0 | No PLL running. |
| `UCIE_CLK_CFG_UNGATE_SRC` / `_DELAY` | 0 (PLL lock) / 1024 | |
| `UCIE_CLK_CFG_GATE_PHY_TEST` / `_GATE_SB` | 1 / 1 | An apply gates a clock when it changes that clock's source. |
| `UCIE_TX_DIVIDER_RST` / `UCIE_RX_DIVIDER_RST` / `UCIE_CLK_MANUAL_EN` / `UCIE_FREQ_SEL_AUTO_EN` | 0 | |

So with nothing written, the block needs three board clocks: `bypassClk`, `digitalBypassClk` and
`sidebandBypassClk`. `refClk` is needed only if a PLL is used.

`UcieTLRegs` (`TileLink.scala:1138-1376`):

| Register | Reset | Notes |
|---|---|---|
| `txctl_N_tile` | `TxLaneCtlIO.off` | Every driver segment off, so every TX lane is high-impedance. |
| `rxctl_N` | `zen` = 0, `zctl` = 0, `vrefSel` = 63, `afeOpCycles` = 16, `afeOverlapCycles` = 2, `dctrl` = 0 | Unterminated. Kodiak's RX reset state was the same. |
| Shufflers | Cancel the tile's bit-reversal tree (`Phy.treeBitOrder`) | Except the TX debug lane, which resets to identity. |
| `txClkP`, `txClkN` | `0x55555555`, `0xAAAAAAAA` | A running forwarded clock out of reset, for `TxClkGater`'s idle words and `txClkDirect`. |
| `txValid`, `rxLfsrValid` | 0 | No valid until written. `setup_ucie` writes `0x0F0F0F0F` to both. |
| `rxAlign_*` | 0 | The deserializers' own word boundary until `align_rx_words` measures the partner's. |
| `txLfsrSeed_*`, `rxLfsrSeed_*` | `0x0123456789abcdef` + lane × `0x1111` (`PhyTest.defaultLfsrSeed`) | A distinct stream per lane out of reset, so adjacent lanes don't carry the same bits. TX and RX reset to the same seeds, so a loopback scores clean without seeding. |
| `txTestMode` / `txDataMode` / `rxDataMode` | manual / finite / infinite | |
| `controllerSel` / `mainbandMode` / `sidebandMode` | PhyTest / manual / manual | |
| `txValidLaneSel` / `rxValidLaneSel` | 16 | The dedicated valid lane (`Phy.scala:29-49`). |
| `debugDriverctl_*` | Off | Every observation pad is off. |

### 2.3 Cold-start reset procedure

Do it in this order. Section 3 explains why steps 1-3 must not touch base + 0x0000 to 0x7FFF.

1. **Chip reset.** Only `UcieClkRegs`, at base + 0x10000 to 0x10FFF, is safe to access.
2. **Configure clocks** in `UcieClkRegs` while `ucieRst` = 1 (step 2 of section 4). Nothing in the
   apply sequencer depends on `ucieRst`. Leave `clkEn` = 0 and `rxClkEn` = 0.
3. **Make sure the PHY test clock's source is running.** For a bypass pin, scope it on the board.
   For a PLL, `UCIE_CLK_CFG_BUSY` = 0 and the lock bit is set in `UCIE_PLL_LOCK_OBSERVED`. There's no
   on-chip way to check.
4. **Write `UCIE_UCIE_RST` = 0.** The PHY test and sideband domains leave reset after 3 edges of
   their own clocks. The dividers leave reset with no clock present, so they're in phase.
5. **First access to the PHY test domain:** a single read of `UCIE_TX_TEST_STATE`, which should be 0.
6. **Program the lanes** (`setup_ucie`, section 4 step 4). It does the following:
   1. Asserts and releases `ucieRst` again.
   2. Enables the lanes.
   3. Realigns the dividers with both clocks gated: `clkEn` = 0 and `rxClkEn` = 0,
      `reset_dividers`, then `clkEn` = 1 and `rxClkEn` = 1.
   4. Sets the selects.
   5. Calls `reset_fsms`, which runs the datapath resets and then the FSM resets
      (`Codegen.scala:1647-1741`).

### 2.4 Re-initializing, and recovering from the partner

- **Re-running `setup_ucie`** is safe as long as nothing else is touching the link. Its `reset_ucie`
  can release the dividers against running clocks, but its own divider realign fixes that.
- **After the partner die resets or restarts its TX clock,** realign only this die's RX:
  1. `set_rx_clk_en(base, 0)`. If the loopback lane is in use, also `set_clk_en(base, 0)`, because
     its RX tile runs on the TX clock.
  2. `UCIE_RX_DIVIDER_RST` = 1, then 0.
  3. `set_rx_clk_en(base, 1)` (and `set_clk_en(base, 1)`).
  4. `UCIE_RX_DATAPATH_RST` = 1, then `UCIE_RX_FSM_RST` = 1.

  This is inferred, not tested. A partner reset asynchronously clears its TX tiles and turns its
  drivers off, so its forwarded clock can end with a runt pulse into this die's running RX dividers.
  No generated helper does this today.
- **After changing `controllerSel`, `mainbandMode` or `sidebandMode`,** run `reset_datapath`. The
  queues' PHY ends don't synchronize the selects (`TileLink.scala:2108-2118`; `Codegen.scala:926-939`).

### 2.5 What goes wrong out of order

| Mistake | Result | Recovery |
|---|---|---|
| Any access to the PhyTest or controller registers before `ucieRst` = 0, or while that domain's clock is stopped | The access never completes, and the register port and bus behind it wedge (section 3). | Chip reset only. |
| `ucieRst` = 0 with `clkEn` or `rxClkEn` = 1, or a divider reset released with its clock running | Dividers start on arbitrary edges, so lanes hand words over at the wrong time or a UI off. | `set_clk_en(0)`, `set_rx_clk_en(0)`, `reset_dividers`, then turn both clocks back on. |
| A TX `Dctrl`, `clkPhaseSel` or `txClkPhase` change with `clkEn` = 1 | The delay line moves under an edge; a tile divider can lose a count and its lane stays a UI off (`Codegen.scala:1068-1078`). | `reset_dividers` with both clocks gated. |
| A select change without `reset_datapath` | Queue pointers can be corrupted by a select landing on an edge. | `reset_datapath`. |
| `txExecute` before the receiver was reset | The receiver frames on the first one it sees on valid, possibly mid-burst, so every lane reads ~50%. | Reset the receiver's FSM, then start TX again. |
| A pattern SRAM write after a run with no `txFsmRst` first | The write is dropped; the FSM is `done`, not `idle` (`PhyTest.scala:1346-1353`). | `txFsmRst`, then load. |
| `reset_ucie` from one agent while another agent touches PhyTest | Deadlock (section 3). | Chip reset. |

---

## 3. The register port, and how not to hang it

This is the most important rule for bring-up.

**The structure** (`TileLink.scala:1994-2015`):
- The three register blocks share one register port: a crossbar behind a source shrinker and a
  `TLFIFOFixer`.
- `UcieTLRegs` (base + 0x0000 to 0x3BFF) and `UcieController` (base + 0x3C00 to 0x7FFF) are each
  behind a one-entry asynchronous crossing into their own clock domain.
- `UcieClkRegs` (base + 0x10000 to 0x10FFF) is on the chip clock and answers in the cycle it's asked.

**What hangs:**
- The crossing doesn't accept a request until the destination domain is clocked and out of reset,
  and a request accepted just before that domain's clock stops is never answered. In rocket-chip,
  `AsyncQueueSource` gives `enq.ready = ready_reg && sink_ready`.
- The FIFO fixer then holds back every request to another block until that one completes
  (`TileLink.scala:2000-2006`). That includes the `UcieClkRegs` write that would fix the clock.
- Upstream, a request that can't move blocks everything behind it on the bus.

**The PhyTest block hangs:**
- while `ucieRst` = 1, the reset value;
- when the PHY test clock's source is absent (the `digitalBypassClk` pin by default);
- while an apply holds the PHY test clock's gate for a PLL that doesn't lock;
- after `phyTestClkEn` = 0.

The controller block hangs in the same situations with the sideband clock.

`UCIE_CLK_METER_PHY_TEST` and `UCIE_CLK_METER_SB`, in `UcieClkRegs`, say whether each clock is
running before you touch its block: each counts its clock's cycles over every
`UCIE_CLK_METER_WINDOW_CYCLES` (1024) chip clock cycles, and reads zero for a stopped clock. They
answer in any state, because `UcieClkRegs` runs on the chip clock. A running clock does not take
the domain out of reset, so `ucieRst` still has to be 0.

**Rules:**
1. Ping the block through `UcieClkRegs` first (base + 0x10000 and up).
2. Don't touch base + 0x0000 to 0x3BFF until `ucieRst` = 0 and `UCIE_CLK_METER_PHY_TEST` reads
   nonzero. Don't touch base + 0x3C00 to 0x7FFF until `UCIE_CLK_METER_SB` does too.
3. After any apply that touches the PHY test clock, wait for `UCIE_CLK_CFG_BUSY` = 0 with a
   time-based bound, before any PhyTest access. If it stays busy, `release_clk_gate` is still safe,
   because it's a `UcieClkRegs` write.
4. Only one agent (core, TSI host or debugger) touches a link at a time. Halt the core before poking
   registers from outside.
5. Use 64-bit, 8-byte-aligned accesses.
   - A write to a field wider than 32 bits is dropped silently unless every byte of the field is in
     the write mask. Those fields include `txctl_N_tile` (58 bits), `clkPhaseSel`, the seeds,
     `txDataChunkIn0/1`, `txPacketsToSend`, `rxPacketsToReceive` and `sbTxPacket`. (In rocket-chip,
     `RegMapper.scala` requires `wimask = mask.andR`.)
   - A 32-bit read of a 64-bit counter returns half of it.
6. Keep remote (manager-port) addresses out of tooling while PhyTest owns the mainband. The manager
   port isn't ready with no TL path selected (`TileLink.scala:2535-2550`).

---

## 4. Bring-up plan: power-on to the first clean LFSR run

Each step lists the writes, what to observe, and a diagnosis for each failure. The C helpers are in
the generated header. **What the generated helpers do:**
- `run_lfsr` waits until every packet is in, or until the count has stood still for
  `Codegen.packetStallReads` (32) reads after starting to climb, at most 1024 reads. It prints a
  line if it comes back short, and one if no RX words arrive at all before the run. It is still
  correct only for a TX looped to its own RX (step 7).
- `apply_clk_cfg` polls `UCIE_CLK_CFG_BUSY` up to 65536 times and prints a line if the apply never
  finished. `setup_ucie_digital` gives up on link-up after 2^24 reads, also with a line.
- `seed_lfsrs` restores every lane's own reset seed, `UCIE_TRAIN_LFSR_SEED` + lane ×
  `UCIE_LFSR_SEED_LANE_STEP`; the seed registers reset to the same values. No two lanes send the
  same stream (on Kodiak a shared seed inflated errors and hid crosstalk).
- `setup_ucie` leaves every observation pad driver and the TX debug lane off;
  `enable_debug_drivers(base, 1)` turns them on for a scope.
- `set_tx_delay` changes only `Dctrl`, keeping driver strength and EQ. Neither it nor
  `set_global_delay` gates `clkEn`: bracket them with `set_clk_en(0)`/`set_clk_en(1)` (step 9).
  `set_global_delay(64)` sets every tap.

`software/phytest.h` in the Iris repository adds time-bounded waits and checks on top of these.

### Step 0: before power-on

- **Board clocks.** Supply `bypassClk`, `digitalBypassClk` and `sidebandBypassClk`, plus `refClk`
  (100 MHz) for PLL use. Scope each at the chip connector. `digitalBypassClk` must be faster than
  every packet clock you'll run (section 1.3). 800 MHz covers up to 24 GT/s.
- **Header.** Regenerate the C header for this chip's parameters.
- **Topology.** Decide the topology: this die's TX looped to its own RX, or two dies. `run_lfsr` is
  correct only for the looped-back case (step 7).

### Step 1: ping the clock registers

```c
reg_read64(base + UCIE_UCIE_RST);          // expect 1
reg_read64(base + UCIE_CLK_CFG_BUSY);      // expect 0
reg_read64(base + UCIE_PLL_LOCK_OBSERVED); // expect 0 (no PLL on)
reg_read64(base + UCIE_MAIN_CLK_SEL);      // expect 3
reg_write64(base + UCIE_CLK_CFG_UNGATE_DELAY, 0x1234);  // write/read check, then restore 1024
```

| Symptom | Cause |
|---|---|
| A read hangs | Not the UCIe clocks. Check the chip clock, the bus path and the address. On a SoC with no bus error device, an unmapped address hangs. |
| Wrong values | Wrong `base`, or a stale header. |

### Step 2: clocks for the UCIe domains

**Option A: bypass pins** (the reset defaults). Write nothing. The PHY test clock is
`digitalBypassClk`, the sideband clock is `sidebandBypassClk`, and the main clock is `bypassClk`.

**Option B: PLLs.** This sequence follows the testbench's clock-source test
(`TileLinkSpec.scala:1467-1485`; the test itself puts the PHY test clock on PLL12 ÷15):

```c
reg_write64(base + UCIE_SB_CLK_SRC_SEL, 0);     // sideband: PLL8
reg_write64(base + UCIE_SB_CLK_DIV, 2);         //           /10 = 800 MHz
reg_write64(base + UCIE_SB_CLK_BYPASS_EN, 0);
use_internal_clk(base, 0, 4);  // PHY test: PLL8 /8 = 1 GHz. Also turns on PLL8, PLL12 and X2, then applies.
// apply_clk_cfg() polls up to 65536 times, and prints if the apply never finished. Then check:
//   UCIE_CLK_CFG_BUSY == 0, bit 0 (PLL8) / bit 1 (PLL12) of UCIE_PLL_LOCK_OBSERVED set,
//   and UCIE_CLK_METER_PHY_TEST reading 1 GHz (2048 at a 500 MHz chip clock).
```

To look at a clock before letting it out, first call
`set_ungate_src(base, UCIE_CLK_UNGATE_SRC_MMIO, 0)`, and later `release_clk_gate(base)`.

Don't call `set_main_clk` before step 4: it writes `clkEn` = 1 (`Codegen.scala:1293-1304`).
`setup_ucie` realigns the dividers anyway.

| Symptom | Cause |
|---|---|
| `UCIE_CLK_CFG_BUSY` stays 1 and the lock bit is 0 | No `refClk`, or the PLL isn't locking. `release_clk_gate(base)` ends the apply. Fall back to option A, or use `set_ungate_src(base, UCIE_CLK_UNGATE_SRC_DELAY, n)` if lock isn't wired. **Don't touch PhyTest registers while busy.** |
| Busy with the lock bit 1 | The source is `mmio`. Call `release_clk_gate`. |

### Step 3: release the UCIe reset, and the first PHY test access

```c
reg_read64(base + UCIE_CLK_METER_PHY_TEST);  // nonzero, or stop: a PhyTest access would hang
reg_read64(base + UCIE_CLK_METER_SB);        // nonzero before touching the controller window
reg_write64(base + UCIE_UCIE_RST, 0);   // clkEn = rxClkEn = 0 still (reset values)
reg_read64(base + UCIE_TX_TEST_STATE);  // the first PhyTest access: expect UCIE_TX_TEST_STATE_IDLE
reg_read64(base + UCIE_CONTROLLER_SEL); // expect UCIE_CONTROLLER_SEL_PHYTEST
reg_read64(base + UCIE_RXCTL + UCIE_RXCTL_VREF_SEL_OFS);  // expect 63
```

A meter reading zero means that clock isn't running: fix its source (the board clock, or the PLL
and divider) before any access to its block. If the `UCIE_TX_TEST_STATE` read hangs anyway, the
domain is still in reset or the clock stopped since: chip reset. Stay out of the controller window (base + 0x3C00
and up) unless the sideband clock is known good.

### Step 4: lane setup

```c
setup_ucie(base);   // leaves the observation drivers off, as link tests want them (Kodiak lesson)
```

`setup_ucie` does the following (`Codegen.scala:1647-1741`):
- Runs `reset_ucie`.
- Writes `UCIE_ENABLE_TX_CTL` (every main driver segment on, EQ off, no delay) to `txctl_0..20`.
  That covers the data, valid, track, both forwarded-clock lanes and the loopback TX lane.
- Writes `zen` = 1 and `zctl` = 0 to `rxctl_0..20`.
- Writes `txClkP`/`txClkN` = `0x55555555`/`0xAAAAAAAA` and `txValid`/`rxLfsrValid` = `0x0F0F0F0F`.
- Turns every observation pad driver and the TX debug lane off (`enable_debug_drivers(base, 0)`).
- Realigns the dividers with both clocks gated, then leaves `clkEn` = `rxClkEn` = 1.
- Sets PhyTest and manual on both bands, and runs `reset_fsms`, which asserts idle and zero counts.

**Before relying on the lanes,** confirm with analog what `zen` = 1 with `zctl` = 0 terminates to.
The wrapper drives a thermometer of `zctl` legs, so `zctl` = 0 turns no leg pins on
(`scala/src/phy/macros/Rx.scala:28-44`).

**To see the TX divided clock on a scope,** briefly enable the clock-mux pad:
- `reg_write64(base + UCIE_DEBUG_CLK_MUX_SEL, 1)`; 1 selects `txDivClk`, 0 selects `sbTxClk`.
- `enable_debug_drivers(base, 1)`, or `reg_write64(base + UCIE_DEBUG_DRIVERCTL + 8*3,
  UCIE_ENABLE_DRIVER_CTL)` for the clock-mux pad alone.
- It should read `TxClk`/16. The cell's output is inverted, which doesn't matter for observation
  (`Common.scala:30-37`).
- Turn the pad off again before link tests (`enable_debug_drivers(base, 0)`).

| Symptom | Cause |
|---|---|
| An assert in `reset_fsms` fails | The TX state isn't idle, or a counter isn't 0. `mainbandMode`/`controllerSel` may not be PhyTest/manual, or the writes may not have landed (check with 64-bit reads). |

### Step 5: line rate and sampling phase

Start at ÷4 off an 8 GHz main clock: 4 GT/s, with a 125 MHz packet clock (section 1.2).

```c
set_main_clk(base, 3 /* bypass pin; 0 = PLL8 */, 2 /* /4 */);  // applies; also sets clkEn = 1
// set_main_clk calls apply_clk_cfg; confirm UCIE_CLK_CFG_BUSY == 0 with a time bound.
set_tx_phase(base, 2);                                   // gates clkEn around the change
set_clk_en(base, 0); set_global_delay(base, 0); set_clk_en(base, 1);
```

**Nominal starting phases.** These put the forwarded clock half a UI from the data, before any
clock-tree/replica mismatch is accounted for. Find the real optimum by sweeping (step 9).

| Rate (8 GHz main) | `txClkPhase` | Global delay taps | Why |
|---|---|---|---|
| ÷4, 4 GT/s | 2 | 0 | Two half-cycles of 62.5 ps = 125 ps = UI/2 |
| ÷2, 8 GT/s | 1 | 0 | 62.5 ps = UI/2 |
| ÷1, 16 GT/s | 0 | 29 | 31.25 ps; `Codegen.digitalGlobalDelayTaps` |

The reset delay (64 taps, ~69.5 ps) with phase 0 sits ~0.11 UI past a data edge at ÷1. That's a
bad starting point (`Codegen.scala:314-323`; `TileLink.scala:1070-1073`).

### Step 6: first-day check on the on-chip loopback lane

This step tests the serializer, driver, AFE and deserializer with no channel. Repeat it at each TX
division and source, to separate TX or clock faults from the channel. Kodiak's bring-up separated TX and clock faults from channel faults like this. Kodiak's own loopback lane had no alignment step and never passed.

The loopback RX lane samples on the same clock that launched the data (`PhyTest.scala:1002-1004`),
so it's a low-rate check. A failure here doesn't condemn the mainband. Its framing starts at the
first one it receives, so use `rxSelfSync` (or a manual pattern with bit 0 set, like Codegen's
`manual_loopback`).

```c
reg_write64(base + UCIE_TEST_TARGET, UCIE_TEST_TARGET_LOOPBACK);
reset_datapath(base);                                    // arms the loopback TX underflow flag
reg_write64(base + UCIE_TX_TEST_MODE, UCIE_TX_TEST_MODE_LFSR);
reg_write64(base + UCIE_TX_DATA_MODE, UCIE_DATA_MODE_FINITE);
reg_write64(base + UCIE_TX_PACKETS_TO_SEND, N);
reg_write64(base + UCIE_RX_SELF_SYNC, 1);                // needs no RX seed, no framing
reg_write64(base + UCIE_RX_DATA_MODE, UCIE_DATA_MODE_FINITE);
reg_write64(base + UCIE_RX_PACKETS_TO_RECEIVE, N);
reg_write64(base + UCIE_TX_FSM_RST, 1);
reg_write64(base + UCIE_RX_FSM_RST, 1);
reg_write64(base + UCIE_TX_EXECUTE, 1);
// wait (time-bounded) for UCIE_RX_PACKETS_RECEIVED >= N, then:
reg_write64(base + UCIE_RX_PAUSE_COUNTERS, 1);
reg_read64(base + UCIE_RX_BIT_ERRORS + UCIE_LOOPBACK_LANE * UCIE_RX_BIT_ERRORS_WIDTH);
reg_write64(base + UCIE_RX_PAUSE_COUNTERS, 0);
reg_read64(base + UCIE_LOOPBACK_TX_FIFO_UNDERFLOW);      // expect 0
reg_read64(base + UCIE_LOOPBACK_RX_FIFO_OVERFLOW);       // expect 0
// restore: UCIE_RX_SELF_SYNC = 0, UCIE_TEST_TARGET = MAINBAND, reset_datapath
```

Self-sync leaves the first two packets unscored, and counts one bit error 1 + (number of LFSR taps)
times (`PhyTest.scala:391-402`).

| Symptom | Cause |
|---|---|
| `UCIE_TX_PACKETS_SENT` stays 0 with `UCIE_TX_TEST_STATE` = run | `txDivClk` isn't running (step 7's table, first row). |
| Received count doesn't move | The loopback RX lane's divided clock isn't running. Check that `UCIE_RX_DIVIDER_RST` = 0 and `UCIE_CLK_EN` = 1; this lane runs on the TX clock. If its divider was released while `clkEn` = 1, it starts at an arbitrary phase, so realign with both clocks gated. |
| Errors at every division | The TX lane `txctl_20` or the RX lane `rxctl_20` (vref, termination), or the same-clock sampling limit. Try the `Dctrl` trims, then `rxctl_20_sampleNegedge` (the backup word handoff, in case the tile updates its word on the other edge). |

### Step 7: first mainband LFSR run

**Topology A: this die's TX looped to its own RX.**

```c
// Distinct per-lane seeds (not seed_lfsrs): Kodiak's top measurement lesson.
for (int l = 0; l < UCIE_NUM_TEST_LANES; l++) {
  uint64_t s = 0x0123456789abcdefULL ^ (0x9e3779b97f4a7c15ULL * (l + 1));  // any nonzero
  reg_write64(base + UCIE_TX_LFSR_SEED + l * UCIE_TX_LFSR_SEED_WIDTH, s);
  reg_write64(base + UCIE_RX_LFSR_SEED + l * UCIE_RX_LFSR_SEED_WIDTH, s);
}
run_lfsr(base, N);       // N = UCIE_TRAIN_PACKETS (16) first
// run_lfsr may return before the burst lands; wait while the count is still moving:
uint64_t prev = ~0ULL, p; int stalls = 0;
for (int w = 0; w < 100000 && stalls < 32; w++) {   // bound by time on silicon
  p = reg_read64(base + UCIE_RX_PACKETS_RECEIVED);
  if (p >= N) break;
  stalls = (p == prev && p > 0) ? stalls + 1 : 0;   // a count still at 0 hasn't started yet
  prev = p;
}
reg_write64(base + UCIE_RX_PAUSE_COUNTERS, 1);
// read UCIE_TX_PACKETS_SENT, UCIE_RX_PACKETS_RECEIVED, and for every lane l in 0..UCIE_TRACK_LANE:
//   UCIE_RX_BIT_ERRORS(+_EARLY, +_LATE) + l * UCIE_RX_BIT_ERRORS_WIDTH
reg_write64(base + UCIE_RX_PAUSE_COUNTERS, 0);
// expect UCIE_TX_TEST_FIFO_UNDERFLOW == 0 and UCIE_RX_TEST_FIFO_OVERFLOW == 0
```

The wait loop above is the testbench's `wait_for_packets` (`TileLinkSpec.scala:848-874`) in C. It
isn't in the generated header.

**Topology B: two dies.** Arm the receiving die R before the transmitting die T starts.
1. On R: write the seeds, `UCIE_RX_DATA_MODE` = finite, `UCIE_RX_PACKETS_TO_RECEIVE` = N, then
   `UCIE_RX_DATAPATH_RST` and `UCIE_RX_FSM_RST`.
2. On R: confirm that `UCIE_RX_IDLE_WORDS_OBSERVED` is moving.
3. On T: write `UCIE_TX_TEST_MODE` = LFSR, `UCIE_TX_DATA_MODE` = finite,
   `UCIE_TX_PACKETS_TO_SEND` = N, `UCIE_TX_FSM_RST`, then `UCIE_TX_EXECUTE`.

R's `rxLfsrSeed_l` must equal T's `txLfsrSeed_l`, and R's `rxLfsrValid` must equal T's `txValid`.
Don't call `run_lfsr` on both dies: each call resets its own TX and RX, and the two calls race.

**What a clean run looks like:**
- `UCIE_TX_PACKETS_SENT` = `UCIE_RX_PACKETS_RECEIVED` = N.
- Nominal errors are 0 on lanes 0 to 17 (data, valid, track).
- Both FIFO flags are 0.

**Classifying each lane**, as the testbench's `score_lanes` does (`TileLinkSpec.scala:883-908`):

| Case | Meaning |
|---|---|
| Packets received < N | "Received nothing". Look at the RX clock and valid first, not the lane. |
| Nominal = 0 | Clean. |
| Early × 4 < nominal, or late × 4 < nominal | The capture was framed a UI late or early. The data are fine and valid's sampling is marginal. |
| Otherwise | Real errors. |

**Diagnosis when the run isn't clean** (see section 5 for the indicators):

| Symptom | Check |
|---|---|
| `UCIE_TX_TEST_STATE` = run and `UCIE_TX_PACKETS_SENT` = 0 | **TX divided clock not running.** The TX queue never accepts. Check `UCIE_CLK_EN` = 1, `UCIE_CLK_CFG_BUSY` = 0 (an apply still waiting), the main clock source (bypass present, PLL locked), and `txDivClk` on the clock-mux pad (step 4). |
| `UCIE_TX_TEST_STATE` stays idle | `mainbandMode` = `tl` or `controllerSel` = UCIe holds the TX FSM, or `txExecute` didn't land. |
| TX sent N; `UCIE_RX_IDLE_WORDS_OBSERVED` and `UCIE_TRACK_ONES` + `UCIE_TRACK_ZEROS` both frozen | **No RX divided clock.** Check `UCIE_RX_CLK_EN`. Check the partner's forwarded clock: its `clkEn`, `txctl_18/19_tile`, and `txClkP`/`txClkN` ≠ 0. Check the RX clock receiver: `rxctl_18` vref and termination. The RX datapath reset can't release without this clock. With TX looped to RX, `UCIE_RX_CLK_FROM_TX_Q` = 1 (both clocks gated across the change, then the RX dividers realigned) separates the clock receiver from the rest of the RX. |
| Track counters moving, `UCIE_RX_PACKETS_RECEIVED` = 0, idle-word count still climbing | **Valid never seen.** Check the valid TX lane (`txctl_16`), the valid RX lane (`rxctl_16` vref and termination), `txValid` (bit 0 should be set), and that `rxValidLaneSel` matches the partner's `txValidLaneSel`. Capture raw words with `UCIE_RX_START_MODE` = immediate and read the capture SRAM (section 6). Moving valid onto track (code 17) rules out the valid lane. |
| `UCIE_RX_PACKETS_RECEIVED` below N and still climbing | The poll window was too short. Wait longer. |
| Every lane ~50% in every framing | Lanes slipped against valid, or bad phase or seeds. In order: `rxSelfSync` = 1 (clean means the data are fine, so look at framing or seeds); then `reset_dividers` with both clocks gated; then sweep the phase (step 9); then check the seeds against the partner's. |
| One lane ~50%, the rest clean | A per-lane divider misstart, Kodiak's "misstart". `reset_dividers` and re-run (step 8). |
| A few errors | Margin. Go to step 9. |
| `UCIE_TX_TEST_FIFO_UNDERFLOW` or `UCIE_RX_TEST_FIFO_OVERFLOW` = 1 | The PHY test clock is slower than a packet clock (section 1.3). |

### Step 8: screen for misstarts and slips before trusting a result

On Kodiak, the previous chip, a random lane misstarted on some bring-ups (`kodiak-bringup/docs/bringup_guide.md`, sections 4 and 6). After every bring-up, run each lane once with `rxSelfSync` = 1
and once normally, keeping the early and late counts. A lane that's ~50% nominal with early or
late ≈ 0 has slipped a UI. A lane that's dirty even under self-sync is broken.

Record the slip statistics against:
- the release order of the two dies;
- termination at reset;
- `rxClkEn` gating;
- gated versus live delay changes.

### Step 9: margining and scale-up

- **Phase sweep.** Sweep the global delay × `txClkPhase` with clocks gated:
  `set_clk_en(0)`, then the delay or phase, then `set_clk_en(1)`.
  - Constants: `UCIE_TRAIN_GLOBAL_CODES`/`_STEP`, `UCIE_TRAIN_EYE_FINE_POINTS`/`_STEP`,
    `UCIE_TRAIN_EYE_DIVS`.
  - `scala/test/src/tilelink/TileLinkSpec.scala` has the training and eye drivers to copy.
- **Per-lane trims.** TX `Dctrl`: `set_tx_delay`, which keeps the driver and EQ bits, with clocks
  gated. RX `Dctrl`: `set_rx_delay`. Per-lane `vrefSel` (`set_rx_vref`). Use the error classes
  (`UCIE_RX_ERR_CLASS_LANE`) to tell which way to move: errors mostly on one value point at the
  threshold, mostly on one edge at the clock.
- **Clock-receiver vref.** Sweep `rxctl_18` (`clkP`) and watch whether the RX clock is present.
  Nothing else sets it, and it feeds every RX lane. On Kodiak the clock receivers peaked sharply near one vref.
- **AFE hand-over.** The RX front end on this chip is not Kodiak's, and its controller will
  change with it, so Kodiak's hand-over settings (its biggest single fix, 256/32 at 500 MHz) are
  not a starting point here. If the current controller is in use, the counts are PHY-test-clock
  cycles, and the counter matches by equality, so a value below the current count waits up to 2^17
  cycles.
- **Duty split.** Invert the forwarded clock (`txClkP` = `0xAAAAAAAA`, `txClkN` = `0x55555555`) to
  separate TX duty from RX duty.
- **Crosstalk.** Pick a victim lane and set the others up as aggressors with the per-lane sources:
  quiet (`txLaneFixed` with `txFixedWord` = 0), toggling in step (their LFSR streams), or toggling
  against the victim (`txLaneInvert`). The partner's RX expects the same through `rxLaneFixed`,
  `rxLaneInvert` and `rxFixedWord`. Record the victim's counts, classes and error positions.
  Don't turn a driver off instead: on Kodiak an undriven neighbour was worse.
- **Long runs.**
  - Set `UCIE_TX_DATA_MODE` and `UCIE_RX_DATA_MODE` to infinite.
  - Arm `UCIE_RX_CAPTURE_MODE` = error with `rxTriggerLaneMask` excluding unused lanes.
  - Read counters under `UCIE_RX_PAUSE_COUNTERS`.
  - Check both FIFO flags, `UCIE_RX_LANE_ERRORED` and `UCIE_PLL_LOCK_LOST` at the end.
- **Scale-up.** Move to ÷2, then ÷1 (start at 29 taps), then PLL12 and X2. Raise the PHY test clock
  above each packet clock first.

---

## 5. Telling clock failures from data failures

| Indicator | Moves when | Use |
|---|---|---|
| `UCIE_PLL_LOCK_OBSERVED`, `UCIE_CLK_CFG_BUSY` | PLL lock; an apply in progress | PLL and apply state (`TileLink.scala:924-960`). |
| `UCIE_CLK_METER_PHY_TEST`, `_SB`, `_TX_DIV`, `_RX_DIV` | Cycles of each clock per `UCIE_CLK_METER_WINDOW_CYCLES` chip cycles | Whether each clock runs, and at what rate, from the block that always answers. `_RX_DIV` at zero means no forwarded clock is arriving; below `_TX_DIV` on a looped-back link, the TX queue is running dry. |
| `UCIE_PLL_LOCK_LOST` | Sticky: a PLL lost lock while enabled | A PLL dropping out mid-run. Write 0 to clear. |
| `UCIE_TX_PACKETS_SENT` | PhyTest enqueues a TX packet | Stuck at 0 in `run` means no `txDivClk`. In infinite mode, with the PHY test clock faster, its rate is the packet rate, which gives a frequency meter. It counts *enqueued* packets, and the queue holds 32. |
| `UCIE_TRACK_ONES` + `UCIE_TRACK_ZEROS` | Every RX word, whatever owns the mainband, +32 per word (`PhyTest.scala:2002-2024`) | RX divided clock alive, and its rate. Use `UCIE_TRACK_CNT_HOLD` to read the pair coherently and `UCIE_TRACK_CNT_RST` to clear. Both stop at 2^32. |
| `UCIE_RX_IDLE_WORDS_OBSERVED` | RX words before recording starts | Moving with no packets received means valid wasn't seen. It stops once recording starts, so use the track counters for clock presence after that. |
| `UCIE_RX_PACKETS_RECEIVED`, error counters, `UCIE_RX_SIGNATURE` | Processed packets | Data quality. Read them under `UCIE_RX_PAUSE_COUNTERS`. |
| `UCIE_RX_LANE_ERRORED`, `UCIE_RX_ERR_POS_MASK`, the error classes | Sticky per lane; per UI; per edge and sent bit | Which lanes, which UIs and which edge. Errors on one UI point at a serializer or deserializer phase; on one edge, at clock duty cycle. |
| `UCIE_TX_TEST_FIFO_UNDERFLOW`, `UCIE_RX_TEST_FIFO_OVERFLOW`, `UCIE_LOOPBACK_*_FIFO_*`, `UCIE_TX_DEBUG_FIFO_UNDERFLOW`, `UCIE_CONTROLLER_RX_FIFO_OVERFLOW` | A queue ran dry or overflowed | A rate problem rather than a channel problem. |
| Capture SRAM (`UCIE_RX_DATA_LANE`, `UCIE_RX_DATA_OFFSET`, `UCIE_RX_DATA_CHUNK`) | Raw received words | Bit-level view; `CaptureMode.error` keeps the window around the first error. |
| Debug pads: `txClk`, `rxClk` (full-rate taps), `rxData` (`UCIE_DEBUG_RX_LANE`/`_BIT`), `clkMux` (`sbTxClk`/`txDivClk`) | | Off-chip observation. These are PHY-test-domain registers, and may disturb the link (Kodiak). |


---

## 6. PhyTest mode reference

The registers are documented at their declarations in `PhyTestRegsIO`
(`scala/src/phytest/PhyTest.scala:104-527`) and `UcieTLRegsIO`
(`scala/src/tilelink/TileLink.scala:184-220`). Below is what each does and when to use it.

### 6.1 Lane numbering

There are two numberings, so check which one a register uses.

| Numbering | Data | Valid | Track | Others | Used by |
|---|---|---|---|---|---|
| PHY lane | 0-15 | 16 | 17 | 18 `clkP`, 19 `clkN`, **20** the PhyTest loopback pair | `txctl_N_*`, `rxctl_N_*`, `rxDebugLane` (0-17) (`Phy.scala:8-24,51-59`; `TileLink.scala:1458-1464`) |
| Test lane (`UCIE_NUM_TEST_LANES` = numLanes + 3) | 0-15 | 16 `UCIE_VALID_LANE` | 17 `UCIE_TRACK_LANE` | **18** `UCIE_LOOPBACK_LANE` | seeds, error counts, `txInjectLane`, `rxTriggerLaneMask`, `rxDataLane`, the pattern and capture SRAMs (`PhyTest.scala:584-592`) |
| Valid-lane select code | 0-15 | 16 (default) | 17 | | `txValidLaneSel`, `rxValidLaneSel` |

### 6.2 General

| Register | What it does | When to use it |
|---|---|---|
| `UCIE_CONTROLLER_SEL` | PhyTest (0) or the UCIe digital stack (1) owns both bands. | Leave it on PhyTest for bring-up. Run `reset_datapath` after any change. |
| `UCIE_MAINBAND_MODE`, `UCIE_SIDEBAND_MODE` | Per band under PhyTest: manual (PhyTest drives) or `tl` (TileLink frames). `tl` holds PhyTest's TX FSM, and its RX too unless `rxMonitor` is set. If both are `tl`, the mainband wins. | TileLink-over-PHY tests after PhyTest is clean. |
| `UCIE_TEST_TARGET` | The mainband or the on-chip loopback pair. | Loopback for the first-day check (step 6). |
| `UCIE_TX_VALID_LANE_SEL`, `UCIE_RX_VALID_LANE_SEL` | Which physical lane carries or frames on valid. The displaced lane's data is **not** sent. | Working round a broken valid lane. The two dies must agree. |

### 6.3 TX

| Register | What it does | When to use it |
|---|---|---|
| `UCIE_TX_TEST_MODE` | Manual (pattern SRAM) or LFSR. | LFSR for BER. Manual for fixed patterns and bit-order checks. |
| `UCIE_TX_DATA_MODE`, `UCIE_TX_PACKETS_TO_SEND` | Finite (that many packets) or infinite (LFSR runs on; manual repeats). | Finite for sweeps, infinite for long runs. |
| `UCIE_TX_LFSR_SEED` + l·`_WIDTH` | 64-bit seed per test lane, loaded at `txFsmRst`. Resets to a different seed per lane (`UCIE_TRAIN_LFSR_SEED` + l × `UCIE_LFSR_SEED_LANE_STEP`). Valid's slot is unused. A seed of 0 sends zeros. | Keep every lane on its own seed; `seed_lfsrs` restores the defaults. |
| `UCIE_TX_LANE_FIXED`, `UCIE_TX_LANE_INVERT`, `UCIE_TX_FIXED_WORD` | LFSR mode: per test lane, send `txFixedWord` every packet, or the lane's stream inverted, instead of the stream. Fixed wins. Valid's bits are ignored; the LFSRs keep advancing; injection, `txTrackClk` and `txValidLaneSel` act on top. No effect in manual mode. | Crosstalk aggressor/victim patterns, static levels, polarity checks. The partner's RX needs the matching `rxLane*`. |
| `UCIE_TX_QUEUE_LIMIT` | Caps the words PhyTest keeps in its TX queue; 0 leaves it uncapped (the whole 32-word queue). Resets to 16. | Lower latency (section 8). A cap below the queue's synchronizer round trip shows up as `UCIE_TX_TEST_FIFO_UNDERFLOW`: 10 is the least that holds with the PHY test clock at the packet clock, 8 with it at 1.6 times. |
| `UCIE_TX_EXECUTE` | Starts a run from idle. Ignored in `run` **and in `done`**. | After `txFsmRst`. |
| `UCIE_TX_FSM_RST`, `UCIE_TX_DATAPATH_RST` | See section 2.1. | Before every run, and after any select change. |
| `UCIE_TX_PACKETS_SENT`, `UCIE_TX_TEST_STATE` | Packets enqueued; idle/run/done. | Progress, and TX clock presence. |
| `UCIE_TX_VALID` | The valid word in LFSR mode. In manual mode valid comes from SRAM slot 16. Keep bit 0 set so the RX frames on packet boundaries. | `UCIE_DEFAULT_VALID`. |
| `UCIE_TX_CLK_P`, `UCIE_TX_CLK_N` | The forwarded-clock words. | Defaults; swap them to invert the forwarded clock. |
| `UCIE_RX_ALIGN` + lane × `UCIE_RX_ALIGN_WIDTH` | How many UI later than the deserializer's own each RX lane's word starts: data lanes, then valid, then track. `align_rx_words` sets every lane to `UCIE_RX_FRAME_OFFSET` (section 8.4). | Before the UCIe controller takes the lanes. |
| `UCIE_TX_CLK_DIRECT` | The forwarded clock comes straight from `txClkP`/`txClkN`, whichever controller is selected. It runs through empty queues and datapath resets and overrides gating (`TileLink.scala:200-204,2750-2753`). | To keep the partner clocked through resets or controller changes. |
| Pattern SRAM: `UCIE_TX_DATA_LANE_GROUP` (groups of 4 test lanes; group 4 is valid/track/loopback), `UCIE_TX_DATA_OFFSET` (row 0 to `UCIE_SRAM_PACKETS` − 1), `UCIE_TX_DATA_CHUNK_IN0` (lanes 4g, 4g+1), `_IN1` (lanes 4g+2, 4g+3), `UCIE_TX_WRITE_CHUNK`; readback `UCIE_TX_DATA_CHUNK_OUT0/1` | Loads one row for four lanes. **Accepted only in idle.** | Manual patterns. Do `txFsmRst` first. |
| `UCIE_TX_MANUAL_REPEAT_PERIOD` | Rows per period; 0, or more than the SRAM depth, means all of it. | Short repeating patterns. |
| `UCIE_TX_BURST_PACKETS`, `UCIE_TX_IDLE_PACKETS` | Bursts of packets separated by idle packets; the pattern doesn't advance across the gaps. | Burst tests; the RX needs `rxBurstMode`. |
| `UCIE_TX_CLK_GATE`, `UCIE_TX_CLK_PREAMBLE` (packets), `UCIE_TX_CLK_POSTAMBLE` (units of `UCIE_CLK_POSTAMBLE_UI` = 2 UI, one forwarded clock cycle) | Forwarded clock only around data, as UCIe's strobe mode gates it, in LFSR and manual mode and for TileLink frames alike. `TxClkGater` at the PHY end does it, with the same settings for the UCIe controller once it negotiates strobe mode (section 8.3). Reset the partner's datapath with the clock running. Every burst scores clean only with a postamble that's a multiple of 32 UI; the last burst arrives whole only with enough postamble (section 8). They reset to no preamble, as UCIe sends, and one packet of postamble (32 UI, 16 units), which is enough once the link is trained for it (section 8.3). With no preamble, centre the forwarded clock first or the first UI of every burst is lost. | Clock-gating tests, and finding the minimum postamble. |
| `UCIE_TX_CLK_PARK_ALTERNATE` | Parks a gated clock at differential high and low in turn, as UCIe asks; 0 parks it low every time. Resets to 1. A high park holds back the postamble's last falling edge until `PhyTest.ClkPreconditionUi` = 2 UI before the clock runs again, so a gap carries the same edges either way (section 8.3). The last register in the map. | Falling back to low parks if alternating ever misbehaves. |
| `UCIE_TX_TRACK_CLK`, `UCIE_TX_TRACK_WORD` | Sends a fixed word on track (a clock by default). | Track-based measurements; clear that lane from `rxTriggerLaneMask`. |
| `UCIE_TX_INJECT_ERROR`, `UCIE_TX_INJECT_LANE` | Flips bit 0 of the next packet on one test lane. | Checking that the counters and capture work end to end. |

### 6.4 RX

| Register | What it does | When to use it |
|---|---|---|
| `UCIE_RX_DATA_MODE`, `UCIE_RX_PACKETS_TO_RECEIVE` | Finite or infinite. | Finite for sweeps. Left running, the RX scores the idle zeros after a finite TX run. |
| `UCIE_RX_LFSR_SEED` + l·`_WIDTH`, `UCIE_RX_LFSR_VALID` | The expected seeds and valid word. They must match the partner's TX. | |
| `UCIE_RX_START_MODE` | Start at the first one on valid (default), or on the first word received (immediate). | Immediate to look at raw bits on a link nothing can frame. |
| `UCIE_RX_SELF_SYNC` | Scores each LFSR lane against its own history, with no seed and no framing. The first 2 packets aren't scored, and one error counts 1 + (number of taps) times. | Separating a data problem from a framing or seed problem; the loopback lane. |
| `UCIE_RX_MANUAL_CHECK` | Scores against this die's TX pattern SRAM, loaded with the partner's pattern. Shares the SRAM port, so this die can't send a manual pattern at the same time. | Manual-pattern BER. |
| `UCIE_RX_LANE_FIXED`, `UCIE_RX_LANE_INVERT`, `UCIE_RX_FIXED_WORD` | Per test lane, score against `rxFixedWord`, or the reference inverted, at all three framings. Applies under `rxSelfSync` too; ignored under `rxManualCheck` and for valid. | Matching the partner's `txLane*`. |
| `UCIE_RX_BURST_MODE` | Skips idle packets between bursts. | With TX bursts. |
| `UCIE_RX_MONITOR` | Keeps the RX FSM running under `tl`. | Capturing TileLink traffic. |
| `UCIE_RX_PACKETS_RECEIVED`, `UCIE_RX_BIT_ERRORS(_EARLY/_LATE)` + l·`_WIDTH`, `UCIE_RX_SIGNATURE` | Counts and scores. Early/late are the same capture framed ±1 UI. The signature is a MISR over the counted packets, modelled by `PhyTest.signatureNext`. | Read under `UCIE_RX_PAUSE_COUNTERS`, which freezes all of them together (`PhyTest.scala:1572-1587`). |
| `UCIE_RX_IDLE_WORDS_OBSERVED` | Free-running 32-bit count of words before recording starts. | Shows whether the RX clock and datapath are alive before a run. |
| `UCIE_RX_LANE_ERRORED`; `UCIE_RX_ERR_POS_LANE`, `UCIE_RX_ERR_POS_MASK` | Sticky: a bit per lane set at its first nominal error; per lane, a bit per packet UI an error has landed on. Only the target's lanes collect bits. Cleared by `rxFsmRst`, not paused. | One read screens every lane; repeat offenders on one UI point at a serdes phase. |
| `UCIE_RX_ERR_CLASS_LANE`; `UCIE_RX_ERR_RISE_ONE`, `_RISE_ZERO`, `_FALL_ONE`, `_FALL_ZERO` | One lane's nominal errors by sampling edge (rising: even UIs of the lane's own word, whatever the framing) and by the bit sent (`ONE`: a 1 read as 0). Paused with the counts; reset the RX FSM after changing the lane. | Duty cycle and comparator phase (edge) against threshold offset (value). |
| Capture: `UCIE_RX_CAPTURE_MODE` (first or error), `UCIE_RX_CAPTURE_START`, `UCIE_RX_CAPTURE_POST_TRIGGER`, `UCIE_RX_TRIGGER_LANE_MASK`, `UCIE_RX_CAPTURE_TRIGGERED`, `UCIE_RX_CAPTURE_TRIGGER_PACKET`; readout `UCIE_RX_DATA_LANE`, `UCIE_RX_DATA_OFFSET`, `UCIE_RX_DATA_CHUNK` (32 bits) | `UCIE_SRAM_PACKETS` packets per lane. Error mode keeps a window around the first error on an unmasked lane. | Bit-level debug. Mask lanes that aren't carrying their pattern, or they trigger on the first packet. |

### 6.5 Status and track

| Register | What it does |
|---|---|
| `UCIE_TX_TEST_FIFO_UNDERFLOW`, `UCIE_RX_TEST_FIFO_OVERFLOW` | The mainband crossings lost words: the TX queue ran dry partway through a run, or the RX queue overflowed. Cleared by the datapath reset. |
| `UCIE_RX_FRAME_OFFSET` | The UI of the RX's own word that the run's first packet started on: where the partner TX's words start. `align_rx_words` reads it. |
| `UCIE_LOOPBACK_TX_FIFO_UNDERFLOW`, `UCIE_LOOPBACK_RX_FIFO_OVERFLOW`, `UCIE_TX_DEBUG_FIFO_UNDERFLOW` | The same, for PhyTest's own lanes. |
| `UCIE_CONTROLLER_RX_FIFO_OVERFLOW` | The UCIe controller's RX queue lost a word while the controller had the lanes: the packet clock outran the sideband clock. Cleared by the RX datapath reset. |
| `UCIE_CLK_METER_PHY_TEST`, `_SB`, `_TX_DIV`, `_RX_DIV` (in `UcieClkRegs`) | Each clock's cycles per `UCIE_CLK_METER_WINDOW_CYCLES` chip cycles: MHz = count × chip MHz / 1024. Readable whatever the rest of the block is doing. |
| `UCIE_PLL_LOCK_LOST` (in `UcieClkRegs`) | Sticky per PLL (PLL8 bit 0): lost lock while enabled. Write 0 to clear. |
| `UCIE_TRACK_ONES`, `UCIE_TRACK_ZEROS`, `UCIE_TRACK_ONES_EVEN`, `UCIE_TRACK_CNT_RST`, `UCIE_TRACK_CNT_HOLD` | Ones and zeros on the track lane over every RX word. "Even" counts the even-UI ones, one sampling edge's share. |

### 6.6 Lane controls

| Register | Fields | Notes |
|---|---|---|
| `UCIE_TXCTL` + N·`UCIE_TXCTL_WIDTH` + `UCIE_TXCTL_TILE_OFS` | 58 bits, MSB first: ENP[9], ENN[9], ENP_EQ[4] (active low), ENN_EQ[4], Dctrl[32]. LSB positions are in `UCIE_TX_CTL_*_LSB`. | High-impedance is ENP = ENN = ENN_EQ = 0 with ENP_EQ all ones. Change `Dctrl` only with `clkEn` = 0. Write 64 bits (`scala/src/phy/macros/Tx.scala`). |
| `..._TXCTL_SHUFFLER_OFS` + j·`_WIDTH`, `..._TXCTL_SAMPLE_NEGEDGE_OFS` | Per-bit permutation; the backup hand-off edge. | The reset shuffler cancels the tile's bit-reversal tree. Use `sampleNegedge` if a lane's hand-off timing fails (`Phy.scala:502-529`). |
| `UCIE_RXCTL` + N·`UCIE_RXCTL_WIDTH` + `_ZEN_OFS`, `_ZCTL_OFS`, `_VREF_SEL_OFS`, `_AFE_BYPASS_EN_OFS`, `_AFE_BYPASS_OFS`, `_AFE_OP_CYCLES_OFS`, `_AFE_OVERLAP_CYCLES_OFS`, `_SHUFFLER_OFS`, `_SAMPLE_NEGEDGE_OFS`, `_DCTRL_OFS` | Termination; the 7-bit reference ladder; the two-sampler hand-over (A, A+B, B, ...) or a fixed AFE state; RX data delay (more taps sample earlier). | The clock lanes (18, 19) use only the AFE fields; 19's output is unused. See `scala/src/phy/RxAfeCtl.scala` and `scala/src/phy/macros/Rx.scala`. |

### 6.7 Debug circuitry and the sideband tester

| Register | What it does |
|---|---|
| `UCIE_DEBUG_DRIVERCTL` + 8·i | Pad drivers for the `txClk`, `rxClk`, `rxData` and `clkMux` bumps: `{pu[6], pd[6], en, en_b}`. `UCIE_ENABLE_DRIVER_CTL` for on, `UCIE_DISABLE_DRIVER_CTL` (0x1) for off, or `enable_debug_drivers` for all four and the TX debug lane. Off out of reset and after `setup_ucie`. |
| `UCIE_DEBUG_CLK_MUX_SEL` | 0 `sbTxClk`, 1 `txDivClk`. The other codes are tied off. |
| `UCIE_DEBUG_RX_LANE`, `UCIE_DEBUG_RX_BIT` | The RX lane (0-17) and bit, after the shuffler, that the `rxData` pad shows. |
| TX debug lane: `UCIE_DEBUG_TXCTL_TILE`, `UCIE_DEBUG_TXCTL_SHUFFLER`, `UCIE_DEBUG_TX_TEST_MODE`, `_DATA_MODE`, `_LFSR_SEED`, `_FSM_RST`, `_EXECUTE`, `_MANUAL_REPEAT_PERIOD`, `_PACKETS_TO_SEND`, `UCIE_DEBUG_DATA` (16 × 64 bits = 32 packets, low half first), `UCIE_DEBUG_TX_TEST_STATE`, `UCIE_DEBUG_TX_PACKETS_SENT` | One TX lane on its own bump for the scope. Its shuffler resets to identity, so the wire order is the tile's tree order unless reprogrammed. |
| `UCIE_SB_TX_PACKET`, `UCIE_SB_TX_SEND`, `UCIE_SB_TX_BUSY`, `UCIE_SB_RX_PACKET`, `UCIE_SB_RX_VALID`, `UCIE_SB_RX_POP`, `UCIE_SB_RX_OVERFLOW`, `UCIE_SB_RX_RST` | One 64-bit sideband packet each way, on the sideband clock, with `sidebandMode` = manual (`scala/src/phytest/SidebandTest.scala`). |

### 6.8 Generated helpers at a glance

| Helper | What it writes | Watch out for |
|---|---|---|
| `reset_datapath` | TX and RX datapath resets | The RX side releases only with an RX clock. |
| `reset_fsms` | `reset_datapath`, TX/RX/debug FSM resets, `assert`s | |
| `reset_ucie` | `ucieRst` 1 then 0 | Wipes `UcieTLRegs`. Deadlocks if another agent is mid-access. |
| `reset_dividers` | Both divider resets, 1 then 0 | Gate both clocks first. |
| `set_clk_en`, `set_rx_clk_en` | `clkEn`, `rxClkEn` | Immediate. |
| `apply_clk_cfg` | `clkCfgApply`, then up to 65536 reads of busy | Prints if the apply never finished. |
| `use_internal_clk(src, div)` | All PLLs and X2 on, PHY test source and divider, bypass off, apply | Gates the PHY test clock until lock. |
| `set_main_clk(src, div)` | `mainClkSel`, `txClkDiv`, **`clkEn` = 1**, apply | |
| `set_tx_phase(h)` | Gated `txClkPhase` | |
| `set_global_delay(t)` | `clkPhaseSel` = (1 << t) − 1, every tap at t ≥ 64 | Not gated. |
| `set_tx_delay(l, t)` | Read-modify-write of `Dctrl` alone | Not gated. Keeps driver strength and EQ. |
| `set_rx_delay`, `set_rx_vref` | `rxctl` `dctrl`, `vrefSel` | |
| `set_ungate_src`, `release_clk_gate`, `set_rate_cfg`, `set_freq_sel_auto`, `set_clk_manual` | Clock-register fields | |
| `enable_debug_drivers(en)` | Every observation pad driver and the TX debug lane, on or off | |
| `setup_ucie` | Section 4, step 4 | Observation drivers off; the global delay is left at the reset 64 taps. |
| `seed_lfsrs` | Every lane's own default seed, TX and RX | |
| `run_lfsr(n)` | `reset_fsms`, a settle wait (up to 4096 reads), a finite LFSR run, a packet wait that ends when the count stops climbing | Prints if short. Correct only for TX looped to RX. |
| `setup_ucie_digital` | UCIe digital stack bring-up | Gives up on link-up after 2^24 reads, with a message. |

---

## 7. Known limitations

1. **The register port can wedge.** An access to the PhyTest or controller block while it's held
   in reset or unclocked hangs the port and the bus behind it. Only a chip reset recovers
   (section 3).
2. **Clock observability is by meter, not by guard.** The clock meters say whether the PHY test and
   sideband clocks run, from a block that always answers; nothing stops an access made while they
   read zero.
3. **The PHY test clock must outrun every packet clock.** 32 GT/s has no margin with a 1 GHz PHY
   test clock (section 1.3).
4. **During a run PhyTest's TX queue holds up to 16 packets** out of reset, because PhyTest outruns
   the packet clock and fills it up to `UCIE_TX_QUEUE_LIMIT` (section 8); 0 lets it fill the whole
   queue (depth `UcieTLParams.queueParams`). Between runs and bursts it is empty: PhyTest queues
   only data. `txPacketsSent` counts enqueued packets.
5. **The RX frames once per run,** on the first one it sees on valid (or on the first word in
   immediate mode). It doesn't re-frame, so a clock postamble that isn't a whole number of packets
   breaks every burst after the first (`PhyTest.scala:218-222`).
6. **`rxIdleWordsObserved` stops counting once recording starts.**
7. **SRAM limits.** Pattern SRAM writes are accepted only in idle. `rxManualCheck` borrows the TX
   SRAM's read port. Each SRAM holds `UCIE_SRAM_PACKETS` packets per lane, and the TX debug lane
   holds 32.
8. **The loopback RX lane is sampled by its own launch clock,** with only the per-lane trims
   (32 × ~0.16 ps) to move it (`Codegen.scala:403-412`).
9. **Phase steps and the main-clock rate.** Phase steps are half a main-clock period. Below ~7.2 GHz
   the global line can't bridge them, and at ÷1 only 0 and 180° coarse phases exist.
10. **The track lane is clocked from the in-phase TX clock,** like the data lanes
    (`ucie_clk_dist_network.sv`). So the track counters can probably not act as a mid-eye drift
    sensor the way Kodiak's track lane did (inference).
11. **No RX-only divider realign helper,** and `reset_dividers` stops both directions.
12. **Fields wider than 32 bits need 64-bit writes;** 32-bit writes are dropped silently.
13. **Moving valid onto another lane doesn't move that lane's data.**
14. **The UCIe controller's RX crossing overflows** once the RX divided clock outruns the 800 MHz
    sideband clock, above 25.6 GT/s. `UCIE_CONTROLLER_RX_FIFO_OVERFLOW` reports it on silicon, and a
    simulation assertion in simulation. This doesn't affect PhyTest.
15. **The behavioral models** (`scala/resources/vsrc`) model no analog behavior, wake-up after a
    clock restart, or lock loss. Use `verilog/models` (see `verilog/README.md`) for anything about
    eyes, vref or termination. Their handoff timing does follow the silicon tiles: each tile's ÷16
    leaves reset like the global one, the TX tile (`tx_lane.v`) loads on its rising edge, and the RX
    tile (`rx_data_lane.v`) puts its word out on its falling edge. So does the structural RX tile in
    `verilog/common/rx.sv`, through a bank of flops on that edge behind its deserializer tree, whose
    last latches are transparent while the divided clock is high. The two count edges the same way
    but differ in latency (item 17).
17. **The RX-side figures in section 8 assume the behavioral RX tile's latency.** In it, the word
    the RX divided clock's rising edge reads ended 17 UI before that edge. In the structural tile in
    `verilog/common/rx.sv` it ended 31 UI before: each latch level of the tree adds delay, 14 UI in
    all. Which of the two the silicon tile is closer to decides section 8's receiver figures:

    | RX tile latency | 17 UI (behavioral) | 31 UI (structural) |
    |---|---|---|
    | Latency, TX dequeue to RX enqueue | 2.5 to 62.5 UI | 16.5 to 76.5 UI |
    | Minimum postamble over every alignment | 18 to 48 UI | 32 to 62 UI |
    | 32 UI postamble after section 8.3's training | every packet | 7 of 8 packets |
    | 64 UI postamble, untrained | every packet | every packet |

    The 31 UI column was measured by rerunning the latency and gated-burst Verilator tests with the
    behavioral tile given the structural tree's 14 UI. The packet missing is the run's last: each
    burst's last word reaches the RX queue only once the next burst's clock arrives. At 31 UI, a
    32 UI postamble only works if every burst ends on an RX word's last UI, and gaps that move the
    boundary in 8 UI steps can't get there from a divider realign: the bursts need 33, 41, 49 or 57
    edges. A
    training gap of 34 UI would, but its gated period isn't a multiple of 8 UI. Two packets of
    postamble cover every alignment at either latency.
16. **The UCIe controller's RX word alignment is measured with PhyTest.** `align_rx_words` (section
    8.4) needs the partner's TX sending PhyTest's LFSR run while it measures: in loopback that is
    this die's own, and `setup_ucie_digital` calls it. Two dies need the partner's PhyTest run at
    the same time. The alignment holds until the RX dividers are next reset, or a gated gap carries
    a number of edges that isn't a whole word.

---

## 8. Latency, and the forwarded clock postamble

Measured in simulation against the behavioral lanes at 16 GT/s by
`UcieTL should time PhyTest's path and its minimum postamble using Verilator`
(`scala/test/src/tilelink/TileLinkSpec.scala`, `PhyTestLatencyTestDriver`). The lanes are looped back
with no channel delay, the forwarded clock is centred on the data (global delay 29 taps), and
gated bursts have no preamble, as in UCIe. The analog lanes add their own delays. The structure
below is what the digital and the tiles' handoff timing set.

That timing is the silicon tiles' (section 7, item 15). Every tile's ÷16 leaves reset in step with
the global ÷16 for its direction, whose output is `clkout_3`:

- **TX:** a tile loads its word on the rising edge of its ÷16. `txDivClk` is `clkout_3` inverted, so
  the TX queue presents each word half a word (16 UI) ahead of the load.
- **RX:** a tile puts its word out on the falling edge of its ÷16. `rxDivClk` is `clkout_3` itself,
  so the RX queue enqueues each word half a word after it appears.

### 8.1 Latency

**Definition:** from the `txDivClk` edge on which the TX queue dequeues a word (`deq.fire`) to the
`rxDivClk` edge on which the RX queue enqueues the RX word holding that word's first UI, and the
one holding its last. The time a word spends waiting in the TX queue is not part of it.

The RX's word boundary is not aligned to the TX's packets, so the measurement is made at every
alignment:

| | Every alignment |
|---|---|
| Dequeue edge to the word's first UI at the bump | −16 UI |
| Dequeue to RX enqueue, first UI's word | 2.5 to 32.5 UI |
| Dequeue to RX enqueue, last UI's word | 32.5 to 62.5 UI |
| With the TX lanes on their backup handoff | one word (32 UI) more |
| With the RX lanes on their backup handoff | unchanged in the model (see below) |

- **The first UI leaves before the dequeue edge.** The TX queue shows a word on its output for the
  whole `txDivClk` cycle that ends with the dequeue, and the tiles load it halfway through.
- **The extra registers on the divided clocks are already out of the path.** Out of reset the TX
  queue's output register feeds each tile, and each tile's output feeds the RX queue, with nothing
  between (`scala/src/phy/Phy.scala`). The backup `sample_negedge` registers are opt-in, per lane.
  The RX one samples on the falling edge of `rxDivClk`, the edge the tile updates on. The model
  passes the new word through on that edge, so it costs nothing there. On silicon it costs a word,
  as its comment says. It exists in case the tile turns out to update on the other edge.
- **The receiver's part** is finishing the RX word a UI lands in (0 to 31 UI), then half a word
  (16 UI) from the tile's falling edge to the RX divided clock's rising edge, which the RX queue
  takes the word on. The half UI is the centred clock sampling half a UI into each UI.
- **`UCIE_TX_QUEUE_LIMIT`**, 16 out of reset (0 lets the whole 32-word queue fill), caps how many
  words PhyTest keeps queued: the time before the dequeue, not the latency above. PhyTest offers a
  word every cycle of a clock faster than the packet clock, so uncapped it keeps the queue full.
  Too small a cap runs the queue dry, which `UCIE_TX_TEST_FIFO_UNDERFLOW` reports. The closer the
  PHY test clock is to the packet clock, the more room a cap needs, since PhyTest can't make up a
  cycle the cap held it off: at 16 GT/s, 8 is the smallest that holds with an 800 MHz PHY test
  clock and 10 with a 500 MHz one, over 4096 packets. 16 leaves room for jitter the simulation
  doesn't have.
- **No controller queues idle words.** PhyTest queues only data packets, the mainband TileLink path
  only frames that carry a beat or a credit return, and the UCIe controller in ACTIVE only beats.
  `TxClkGater` (`scala/src/phytest/TxClkGater.scala`), between the selected controller's queue and
  the lanes, sends a word of its own whenever the queue has nothing due: valid low, the data lanes
  at zero, track as that controller runs it, and the clock running or gated. So nothing waits
  behind idle words: a run, burst or frame starts behind an empty queue, about 4 to 5 words of
  crossing, and only a run longer than that fills the queue up to the cap. When idle words were
  queued too, the queue sat full whenever the controller's clock outran the packet clock. At 8
  GT/s with a 500 MHz bus, a TileLink read's round trip over the mainband loopback was 140 bus
  cycles; it is now 36.

### 8.2 The postamble a burst needs

`UCIE_TX_CLK_POSTAMBLE` is in units of `UCIE_CLK_POSTAMBLE_UI` = 2 UI, one forwarded clock cycle.
Each UI of postamble is one clock edge. After the edge that samples a burst's last data UI, the
receiver needs 17 more: one for the tile to put out the RX word that UI ends, and 16 to the RX
divided clock's rising edge, which its queue takes the word on. The first lands on the next rising
edge, since an RX word's last UI is a falling-edge sample, and that edge is also the tile's ÷16
falling. If the last data UI is not the last of its RX word, add the 1 to 31 UI it takes to finish
the word first.

| Minimum postamble, over every RX alignment | Measured |
|---|---|
| Default RX handoff | **18 to 48 UI** |
| Backup RX handoff (`sample_negedge`) | 18 to 48 UI |
| Manual mode, at the worst alignment | 48 UI |

- **18 to 48 UI is the receiver's own need**: 17 edges, rounded up to the 2 UI unit, when the last
  data UI ends an RX word, and 31 more when it starts one. The forwarded clock tiles load in step
  with the data tiles, so the clock pattern of a burst's last word goes out with that word and the
  postamble starts right after it.
- **Centre the forwarded clock before gating it.** With no preamble, the first edge after the gate
  opens has to sample the burst's first UI. At the global delay's reset value (64 taps, about
  1.1 UI at 16 GT/s) the clock trails the data by more than a UI. Each edge then samples the UI
  after its own, and the first data UI is lost: the same 4-packet burst arrives with about 1200 bit
  errors, against none at 29 taps. A preamble (`UCIE_TX_CLK_PREAMBLE`, in packets) hides that.
  UCIe has none, and the register resets to 0.
- **Use a whole number of packets.** That leaves the RX word boundary where it was for the next
  burst. A partial packet moves it by the postamble modulo 32 UI, and PhyTest's RX does not
  re-frame (section 7). One packet (32 UI, the reset value) is enough after the training in
  section 8.3; two (64 UI) cover every alignment untrained.
- **On the bench**, a PhyTest postamble sweep finds the smallest postamble that still delivers the
  last packet at the alignment in effect: between 18 and 48 UI plus whatever the analog lanes add.
- **Manual mode** can test any postamble the register can express, and needs the same minimum as
  LFSR mode: the gating applies to a manual run exactly as to an LFSR one, since every pattern packet
  counts as data. The pattern SRAM has no say in the clock lanes, which come from `txClkP`/`txClkN`
  under the gate's mask.

### 8.3 Gating the UCIe way: one packet of postamble

UCIe's strobe mode asks the TX for at least 16 UI of postamble, no preamble, a gated period that's
a multiple of 8 UI, a parked level that alternates between differential high and low from one gap
to the next, and 1 to 8 UI of differential low before the clock runs again. PhyTest's TX does all
of it at the reset values (`UCIE_TX_CLK_GATE` = 1 is the only write):

- **Postamble and gated period.** 32 UI of postamble, twice UCIe's minimum. Gaps are whole packets,
  so a postamble that's a multiple of 8 UI makes the gated period one too.
- **Parked level.** Parked, each clock lane holds its pattern's UI 1 level, or its UI 0 level on a
  high park: with the usual `txClkP`/`txClkN` patterns, P low and N high, or the reverse. A park
  straight after a data packet, which only a zero postamble or an FSM reset makes, stays low.
- **High parks.** The clock keeps the level of the postamble's last rising edge through the UI it
  would have fallen on, and falls `PhyTest.ClkPreconditionUi` = 2 UI before the next burst, or the
  next run, starts. That falling edge is the one the postamble held back, so a gap carries exactly
  the postamble's edges at either level, and the RX word boundary stays put. The receiver loses
  nothing either: it always needs an odd number of edges, so the one held back never changes the
  postamble it needs.
- **Where it's done.** `TxClkGater`, at the PHY end, makes every word that isn't data, for
  PhyTest, TileLink frames and the UCIe controller alike. PhyTest marks the word that ends a burst,
  and the gater counts the `UCIE_TX_IDLE_PACKETS` gap itself, so it puts the precondition in the
  gap's last word. Between runs it can only start the clock once the next word is queued, so after
  a high park the run's first data waits one word for the precondition.
- **`UCIE_TX_CLK_PARK_ALTERNATE` = 0** parks low every time, if alternating ever breaks something.

One packet of postamble delivers a burst only if its last UI lands 17 UI or more into an RX word
(section 8.2: 17 edges, plus what finishes the word, with 31 available on a high park). The
alignment that a divider realign leaves behind a parked clock is just outside that: the first edge
starts the tile's divider, which finishes its first word 16 UI later, so every burst ends 15 UI
into an RX word and needs 33. One gap of 40 UI moves the boundary 8 UI, after which every burst
ends 23 UI in and needs 25. All of this needs the forwarded clock centred on the data, as
mainband training leaves it: with no preamble, the first edge after a park has to sample the
burst's first data UI. Out of reset the global delay line is at its longest (`clkPhaseSel` all
ones), a little over a UI at 16 GT/s, and there a burst's first UI gets no edge of its own. On the
Iris harness `set_global_delay(29)` centres it. The training, once per link:

1. With the clock running, reset the datapath and FSMs (`pt_arm`, or `reset_fsms`).
2. `UCIE_TX_CLK_GATE` = 1, so the clock parks.
3. On the receiving die, realign the RX dividers behind the parked clock: `set_clk_en(0)` (in
   loopback), `set_rx_clk_en(0)`, `reset_dividers`, then both back to 1.
4. Send one burst with `UCIE_TX_CLK_POSTAMBLE` = 20 (40 UI), then set it back to 16 (32 UI).

After that, a clock that runs or parks for whole packets leaves the boundary alone, so ungating to
reset the FSMs between runs is fine. `test_gated_bursts` in `software/phytest-loopback.c` does
this, at both park settings, and `UcieTL should keep gated bursts whole with a packet of postamble
using Verilator` checks it on the silicon handoff timing, along with the untrained case losing its
last packet. `UcieTL should run the bring-up program's gated bursts at equal rates using Verilator`
runs `test_gated_bursts` step for step at the Iris harness's clocks, the PHY test clock at the
packet clock. Getting down to UCIe's 16 UI would take 16 UI of clock ahead of each burst as well
as a 2 UI earlier global RX divider, which is a change to the divider macro; 32 UI needs nothing.

All of this holds for the behavioral RX tile's latency. If the silicon tile's is closer to the
structural tree's, 31 UI rather than 17 (limitation 17), this training still leaves each burst's
last word in the tile until the next burst, and two packets (64 UI) of postamble is the setting
that works, untrained.

**TileLink frames and the UCIe controller.** The same gating applies to TileLink frames on the
mainband (`UCIE_TX_CLK_GATE` = 1 with `mainbandMode` = `tl`), and to the UCIe controller once it
has negotiated strobe mode and reached ACTIVE, which it reports to the PHY as `mbTxClkGate`.
MBINIT.PARAM's clock mode is taken as 0 = strobe, 1 = continuous; check that against the spec.
`rx_clk_mode_sel` resets to 0, so out of reset the controller asks for strobe. Neither gets
PhyTest's training, so `setup_ucie_digital` sets two packets of postamble (64 UI), which covers
every alignment, and `UcieTL should support simple TL test with the clock gated using Verilator`
runs TileLink frames with the same.

### 8.4 Lining the RX words up for the UCIe controller

Which 32 UI land in an RX word is set by the forwarded clock's first edge after the RX dividers are
realigned, and nothing in the PHY lines that up with the partner TX's words. PhyTest doesn't care:
it frames on valid. The UCIe controller takes each word as it comes, so without help it trains
through MBINIT and then fails MBTRAIN.LINKSPEED, its first LFSR comparison.

`rxAlign_N` (`Phy.scala`, `PhyRegsIO.rxAlign`) starts each RX lane's word that many UI later than
the deserializer's own, out of the previous word and the current one, after the shuffler and
before the RX queues, so PhyTest and the controller both see it. Out of reset it is 0.

`align_rx_words`, which `setup_ucie_digital` calls before handing the lanes to the controller,
measures it:

1. PhyTest takes the mainband with the default clock and valid waveforms, and every `rxAlign_N` at
   0.
2. A four-packet LFSR run. PhyTest's RX frames on the first set bit it sees on valid, which with
   `0x0F0F0F0F` is UI 0 of the partner TX's word, and reports where in its own word that landed as
   `UCIE_RX_FRAME_OFFSET`.
3. Every `rxAlign_N` is set to it. If the RX framed nothing, they stay at 0 and it says so.

The partner's TX has to be sending that run while it measures: in loopback it is this die's own.
Measure after any RX divider reset, and after any gated gap whose edges aren't a whole word. On
the Iris harness both links measure 16 UI, where a divider realign leaves a burst's first UI, and
Iris's `ucie digital loopback test` trains and carries traffic on both.
