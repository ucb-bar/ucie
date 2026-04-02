package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._
//import circt.stage.ChiselStage

class sidebandSwitcher(val myID: BigInt, val sbParams: SidebandParams)
    extends Module {
  val io = IO(new SidebandSwitcherIO(sbParams))

  val node_to_node_below_to_above = Wire(
    Decoupled(UInt(sbParams.sbNodeMsgWidth.W)),
  )
  val node_to_node_above_to_below = Wire(
    Decoupled(UInt(sbParams.sbNodeMsgWidth.W)),
  )

  val outer_node_to_layer_below_subswitch = Module(
    new sidebandOneInTwoOutSwitch(myID, sbParams),
  )
  val outer_node_to_layer_above_subswitch = Module(
    new sidebandOneInTwoOutSwitch(myID, sbParams),
  )

  outer_node_to_layer_below_subswitch.io.outer_node_to_layer <> io.outer.node_to_layer_below
  outer_node_to_layer_above_subswitch.io.outer_node_to_layer <> io.outer.node_to_layer_above

  outer_node_to_layer_below_subswitch.io.inner_node_to_layer <> io.inner.node_to_layer_below
  outer_node_to_layer_above_subswitch.io.inner_node_to_layer <> io.inner.node_to_layer_above

  node_to_node_below_to_above <> outer_node_to_layer_below_subswitch.io.node_to_node
  node_to_node_above_to_below <> outer_node_to_layer_above_subswitch.io.node_to_node

  val outer_layer_to_node_above_subswitch = Module(
    new sidebandTwoInOneOutSwitch(sbParams),
  )
  val outer_layer_to_node_below_subswitch = Module(
    new sidebandTwoInOneOutSwitch(sbParams),
  )

  outer_layer_to_node_above_subswitch.io.node_to_node <> node_to_node_below_to_above
  outer_layer_to_node_below_subswitch.io.node_to_node <> node_to_node_above_to_below

  outer_layer_to_node_above_subswitch.io.outer_layer_to_node <> io.outer.layer_to_node_above
  outer_layer_to_node_below_subswitch.io.outer_layer_to_node <> io.outer.layer_to_node_below

  outer_layer_to_node_above_subswitch.io.inner_layer_to_node <> io.inner.layer_to_node_above
  outer_layer_to_node_below_subswitch.io.inner_layer_to_node <> io.inner.layer_to_node_below
}

class sidebandOneInTwoOutSwitch(val myID: BigInt, val sbParams: SidebandParams)
    extends Module {
  val io = IO(new Bundle {
    val outer_node_to_layer =
      Flipped(Decoupled(UInt(sbParams.sbNodeMsgWidth.W)))
    val inner_node_to_layer = Decoupled(UInt(sbParams.sbNodeMsgWidth.W))
    val node_to_node = Decoupled(UInt(sbParams.sbNodeMsgWidth.W))
  })
  io.inner_node_to_layer.valid := io.outer_node_to_layer.valid && io.outer_node_to_layer
    .bits(58, 56) === myID.U
  io.inner_node_to_layer.bits := io.outer_node_to_layer.bits
  io.node_to_node.valid := io.outer_node_to_layer.valid && io.outer_node_to_layer
    .bits(58, 56) =/= myID.U
  io.node_to_node.bits := io.outer_node_to_layer.bits

  io.outer_node_to_layer.ready := Mux(
    io.outer_node_to_layer.bits(58, 56) === myID.U,
    io.inner_node_to_layer.ready,
    io.node_to_node.ready,
  )
}

class sidebandTwoInOneOutSwitch(val sbParams: SidebandParams) extends Module {
  val io = IO(new Bundle {
    val outer_layer_to_node = Decoupled(UInt(sbParams.sbNodeMsgWidth.W))
    val inner_layer_to_node =
      Flipped(Decoupled(UInt(sbParams.sbNodeMsgWidth.W)))
    val node_to_node = Flipped(Decoupled(UInt(sbParams.sbNodeMsgWidth.W)))
  })
  // indicates whether node_to_node or inner_layer_to_node has priority
  val flag = Wire(Bool())
  // priority is 0 if SBM.isComplete(x), 1 if SBM.isMessage(x), 2 otherwise
  val priority_node_to_node = Wire(UInt(2.W))
  val priority_inner_layer_to_node = Wire(UInt(2.W))

  priority_node_to_node := Mux(
    SBM.isComplete(io.node_to_node.bits),
    0.U,
    Mux(SBM.isMessage(io.node_to_node.bits), 1.U, 2.U),
  )
  priority_inner_layer_to_node := Mux(
    SBM.isComplete(io.inner_layer_to_node.bits),
    0.U,
    Mux(SBM.isMessage(io.inner_layer_to_node.bits), 1.U, 2.U),
  )
  flag := Mux(
    io.node_to_node.valid && io.inner_layer_to_node.valid,
    priority_inner_layer_to_node > priority_node_to_node,
    Mux(io.node_to_node.valid, true.B, false.B),
  )
  // if flag is true, then node_to_node has priority
  io.outer_layer_to_node.valid := io.node_to_node.valid || io.inner_layer_to_node.valid
  io.outer_layer_to_node.bits := Mux(
    flag,
    io.node_to_node.bits,
    io.inner_layer_to_node.bits,
  )
  io.node_to_node.ready := Mux(flag, io.outer_layer_to_node.ready, false.B)
  io.inner_layer_to_node.ready := Mux(
    flag,
    false.B,
    io.outer_layer_to_node.ready,
  )
}

// object FirrtlMain extends App {
//   ChiselStage.emitCHIRRTL(new sidebandSwitcher(0, new SidebandParams()))
// }

// object VerilogMain extends App {
//   ChiselStage.emitSystemVerilog(
//     new sidebandOneInTwoOutSwitch(0, new SidebandParams()),
//   )
// }