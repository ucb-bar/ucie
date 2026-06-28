package edu.berkeley.cs.uciedigital.tilelink

import chisel3._
import chisel3.util._

class CreditCounter(counter_size: Int, buffer_depth: Int) extends Module {
    val io = IO(new Bundle {
        val avail = Output(Bool())
        val used = Input(Bool())
        val ret = Flipped(Valid(UInt(log2Up(buffer_depth).W)))
        val mode = Input(Bool()) // If false, reset counter values to default (credit flow disabled)
    })

    val cred_used = RegInit(0.U(log2Up(counter_size).W))
    val cred_gnt = RegInit(buffer_depth.U(log2Up(counter_size).W))
    val overflow = Wire(UInt((log2Up(counter_size) + 1).W))


    when (io.used) {
        cred_used := Mux(io.mode, cred_used + 1.U, 0.U)
    }

    when (io.ret.valid) {
        cred_gnt := Mux(io.mode, cred_gnt + io.ret.bits, buffer_depth.U)
    }

    overflow := cred_gnt - cred_used
    io.avail := overflow(log2Up(counter_size) - 1, 0) =/= 0.U
}