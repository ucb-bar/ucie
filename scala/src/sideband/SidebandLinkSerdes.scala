/*
  Description: 
    Contains the logic for the controller, serializer, and deserializer for
    the sideband messaging over the physical UCIe link. 
    (ie. The 'SidebandLinkSerdes' module will be conntected to the 
    sideband AFE IO.)

  Notes of implementation:
    * Serializer and deserializer contains async FIFO because the digital
    stack runs at the freqency required by the mainband data rate.  
*/

package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._
import freechips.rocketchip.util.{AsyncQueue, AsyncQueueParams}

/*
  SidebandLinkSerDes
  Description:
    A controller module for the serializer and deserializer.
*/
class SidebandLinkSerDes(val sbParams: SidebandParams)
  extends Module {    
  val msg_w = sbParams.sbNodeMsgWidth
  val sbLink_w = sbParams.sbLinkWidth
  val async_queue_depth = sbParams.sbLinkAsyncQueueDepth
  
  val numDesTimeoutCycles = 512  // TODO: Better parameterization for this -- put in a class

  assert(numDesTimeoutCycles > (msg_w + 64), "Need to atleast let largest message process")

  val io = IO(new Bundle {
    val ctrl = new Bundle {
      val sb_clock = Input(Clock())
      val rxtx_mode = Input(SBRxTxMode())
      val des_timedout = Output(Bool())
    }        
    val serializerIO = new Bundle {
      val in = new Bundle {
        val msg = Flipped(Decoupled(UInt(msg_w.W)))
      }            
      val out = new Bundle {
        val bits = Output(UInt(sbLink_w.W))
        val fw_clock = Output(Bool())            
      }
    }
    val deserializerIO = new Bundle {
      val in = new Bundle {
        val bits = Input(UInt(sbLink_w.W))
        val fw_clock = Input(Bool())            
      }
      val out = new Bundle {
        val msg = Decoupled(UInt(msg_w.W))
      } 
    }
  })

  /*
    TODO: 
    - Set outgoing packet's data and control parity bits
    - Check incoming packet's data and control parity bits
      -- Need to signal an error, and probably drop the packet
      -- Implement once DVSEC registers are implemented
    - If sb_clock is being produced by UCIe PHY, can we use diplomacy connection? 
      ie use ClockSinkNode
    - Make sure resets assertion are async but deasserts are synchronized
      with respective clock 
    - Make sure reset crossing are correct
  */

  val sb_clock = io.ctrl.sb_clock

  // Synchronize the reset with SB clock
  val sb_reset_sync = withClock(sb_clock) { RegNext(RegNext(reset)) }
  
  // Operates at SB freqency
  val link_ser = withClockAndReset(sb_clock, sb_reset_sync) {
                      Module(new SidebandLinkSerializer(sbLink_w, msg_w))
                  }
  val link_des = withClockAndReset(sb_clock, sb_reset_sync) {
                      Module(new SidebandLinkDeserializer(sbLink_w, msg_w, numDesTimeoutCycles))
                  }

  val in_async = Module(new AsyncQueue(UInt(msg_w.W), AsyncQueueParams(depth=async_queue_depth)))
  val out_async = Module(new AsyncQueue(UInt(msg_w.W), AsyncQueueParams(depth=async_queue_depth)))

  // connect serdes input with in_async enqueue
  in_async.io.enq_clock := clock
  in_async.io.enq_reset := reset

  link_ser.io.ctrl.tx_mode := io.ctrl.rxtx_mode
  link_des.io.ctrl.rx_mode := io.ctrl.rxtx_mode
  io.ctrl.des_timedout := link_des.io.ctrl.des_timedout

  in_async.io.enq.valid := io.serializerIO.in.msg.valid
  in_async.io.enq.bits := io.serializerIO.in.msg.bits
  io.serializerIO.in.msg.ready := in_async.io.enq.ready
  // io_async.io.enq <> io.serializerIO.in.msg

  // connect in_async dequeue with serializer input
  in_async.io.deq_clock := io.ctrl.sb_clock
  in_async.io.deq_reset := sb_reset_sync
  
  link_ser.io.in.msg.valid := in_async.io.deq.valid
  in_async.io.deq.ready := link_ser.io.in.msg.ready
  link_ser.io.in.msg.bits := in_async.io.deq.bits
  // io_async.io.deq <> link_ser.io.in.msg

  // connect out_async enqueue with deserializer output
  out_async.io.enq_clock := io.ctrl.sb_clock
  out_async.io.enq_reset := sb_reset_sync
  out_async.io.enq.valid := link_des.io.out.msg.valid
  link_des.io.out.msg.ready := out_async.io.enq.ready
  out_async.io.enq.bits := link_des.io.out.msg.bits

  // connect serdes output with out_async dequeue
  out_async.io.deq_clock := clock
  out_async.io.deq_reset := reset

  io.deserializerIO.out.msg.valid := out_async.io.deq.valid
  out_async.io.deq.ready := io.deserializerIO.out.msg.ready
  io.deserializerIO.out.msg.bits := out_async.io.deq.bits

  // connect out of serializer to serdes out (goes to sb afe)
  io.serializerIO.out.bits := link_ser.io.out.bits
  io.serializerIO.out.fw_clock := link_ser.io.out.clock

  // connect in of deserializer to serdes in (from sb afe)
  link_des.io.in.bits := io.deserializerIO.in.bits
  link_des.io.in.fw_clock := io.deserializerIO.in.fw_clock
}

//  SidebandLinkSerializer
class SidebandLinkSerializer(val sbLink_w: Int, val msg_w: Int) extends Module {
  
  object SerializerState extends ChiselEnum {
    val sIDLE, sBITS_SEND, sBITS_WAIT = Value
  }

  val io = IO(new Bundle {
    val ctrl = new Bundle {
      val tx_mode = Input(SBRxTxMode())
    }  
    val in = new Bundle {
      val msg = Flipped(Decoupled(UInt(msg_w.W)))
    }            
    val out = new Bundle {
      val bits = Output(UInt(sbLink_w.W))
      val clock = Output(Bool())            
    }
  })

  val current_state = RegInit(SerializerState.sIDLE)
  val num_bits_to_send = 64       // As per spec, tansmit in 64 bit chunks
  val num_bits_to_wait = 32       // As per spec, need to wait 32 bits

  val packet = RegInit(0.U(msg_w.W))
  val pkt_opcode = RegInit(0.U(5.W))
  val out_clk_en = Wire(Bool())
  val out_bits_cnt_en = Wire(Bool())
  val wait_bits_cnt_en = Wire(Bool())

  val beat_count = RegInit(0.U(4.W))
  val num_beats = Wire(UInt(4.W))
  val done_sending = Wire(Bool())
  val new_packet_valid = Wire(Bool())
  
  done_sending := beat_count === num_beats
  new_packet_valid := io.in.msg.valid && (current_state =/= SerializerState.sIDLE)
      
  val (out_bits_count, out_bits_done) = Counter(out_bits_cnt_en, num_bits_to_send)
  
  val (wait_bits_count, wait_bits_done) = Counter(wait_bits_cnt_en, num_bits_to_wait)
                                          
  // Packet opcodes without data
  // If any new packet types without data are added, then add opcode here.
  val pkt_type_wo_data =  Seq(SBMsgOpcode.MessageWithoutData, 
                              SBMsgOpcode.ManagementPortMsgWithoutData)
  val is_wo_data = pkt_type_wo_data.map(_.asUInt === pkt_opcode).reduce(_ || _)

  // tx_mode: RAW means send raw bits don't look at opcode (will be 64 bits)
  when(is_wo_data || (io.ctrl.tx_mode === SBRxTxMode.RAW)) {
    num_beats := 1.U       // messages w/o data (1 64 bit chunk)
  }.otherwise {
    num_beats := 2.U       // messages w/ data  (2 64-bit chunk)
  }

  io.out.bits := Mux(out_clk_en, packet(sbLink_w - 1, 0), 0.U)
  io.out.clock := Mux(out_clk_en, clock.asUInt, 0.U)

  // defaults
  io.in.msg.ready := false.B
  out_clk_en := false.B
  out_bits_cnt_en := false.B
  wait_bits_cnt_en := false.B
    
    // state action
  switch(current_state) {
    is(SerializerState.sIDLE) {
      assert(out_bits_count === 0.U, "Output bit counter should start at 0")
      assert(wait_bits_count === 0.U, "Wait bit counter should start at 0")

      io.in.msg.ready := true.B
      out_clk_en := false.B
      out_bits_cnt_en := false.B            
      when(io.in.msg.valid) {                
        beat_count := 0.U
        packet := io.in.msg.bits
        pkt_opcode := io.in.msg.bits(4,0) 
      }
    }
    is(SerializerState.sBITS_SEND) {
      io.in.msg.ready := false.B
      out_clk_en := true.B
      out_bits_cnt_en := true.B
      wait_bits_cnt_en := false.B            
      packet := packet >> sbLink_w.U

      when(out_bits_done) {
        beat_count := beat_count + 1.U
      }
    }
    is(SerializerState.sBITS_WAIT) {
      io.in.msg.ready := false.B
      out_clk_en := false.B
      out_bits_cnt_en := false.B
      wait_bits_cnt_en := true.B

      // finished sending current packet, but there is a valid packet
      // ready -- doing so removes a cycle delay between sending packets
      when (done_sending && new_packet_valid) {
        beat_count := 0.U
        io.in.msg.ready := true.B
        packet := io.in.msg.bits
        pkt_opcode := io.in.msg.bits(4,0) 
      }
    }
  }

  // state transition
  switch(current_state) {
    is(SerializerState.sIDLE) {
      when(io.in.msg.valid && io.in.msg.ready) {                   
        current_state := SerializerState.sBITS_SEND                
      }
    }
    is(SerializerState.sBITS_SEND) {
      when(out_bits_done) {            
        current_state := SerializerState.sBITS_WAIT
      }           
    }        
    is(SerializerState.sBITS_WAIT) {
      when(wait_bits_done) {
        when(done_sending && !new_packet_valid) {
          current_state := SerializerState.sIDLE      // wait for new message
        }.otherwise {
          current_state := SerializerState.sBITS_SEND // more beats to send
        }  
      }              
    }
  } 
}


//  SidebandLinkDeserializer
class SidebandLinkDeserializer(val sbLink_w: Int, val msg_w: Int, val des_timeout_cycles: Int) 
  extends Module {
  val io = IO(new Bundle {
    val ctrl = new Bundle {
      val rx_mode = Input(SBRxTxMode())
      val des_timedout = Output(Bool())
    }        
    val in = new Bundle {
      val bits = Input(UInt(sbLink_w.W))
      val fw_clock = Input(Bool())            
    }
    val out = new Bundle {
      val msg = Decoupled(UInt(msg_w.W))
    }                    
  })

    // Packet opcodes without data
    // If any new packet types without data are added, then add opcode here.
  val pkt_type_wo_data =  Seq(SBMsgOpcode.MessageWithoutData, 
                              SBMsgOpcode.ManagementPortMsgWithoutData)

  
  // Note: 
  //  - This clock stops toggling when there's no data being sent.
  //  - Data is sampled at the negative edge.
  //  - Counter_prev is used to protect against the when the last bit never arrives. Ex: If the
  //    last bit never comes counter_prev == max_bits, and counter == max_bits.
  //    If it does come, then counter_prev == max_bits, and counter == 0.
  val neg_fw_clock = (!io.in.fw_clock).asClock    

  val (async_valid_data, async_data_reg, async_idle_status) = 
  withClockAndReset(neg_fw_clock, reset.asAsyncReset) {

    val recv_done = Wire(Bool())
    val idle_status = Wire(Bool())
    val counter = RegInit(0.U(log2Ceil(msg_w).W))
    val counter_prev = RegNext(counter, 0.U)
    val max_bits = RegInit((msg_w - 1).U(log2Ceil(msg_w).W))
    val data_reg = RegInit(0.U(msg_w.W))
    val valid_data = Wire(Bool())
   
    val is_wo_data = pkt_type_wo_data.map(_.asUInt === data_reg(4,0)).reduce(_ || _)

    // next cycle receive is done and fw_clock will stop toggling
    recv_done := counter === max_bits

    idle_status := counter === 0.U

    when(counter === 5.U) {
      // rx_mode: RAW means read raw bits (will be 64 bits)
      when(is_wo_data || (io.ctrl.rx_mode === SBRxTxMode.RAW)) {
        max_bits := 63.U            // messages w/o data (64 bits read)
      }.otherwise {
        max_bits := 127.U           // messages w/ data  (128 bits read)
      }
    }

    when(recv_done) {
      counter := 0.U 
    }.otherwise {
      counter := counter + 1.U    
    }

    valid_data := (counter_prev === max_bits) && (counter === 0.U)  // all data arrived
    data_reg := data_reg.bitSet(counter, io.in.bits.asBool)


    (valid_data, data_reg, idle_status)
  }

  // Clock phase is shifted by 180 deg -- async crossing
  val valid_sync = RegNext(RegNext(async_valid_data))
  val data_sync = RegNext(RegNext(async_data_reg))
  val idle_status_sync = RegNext(RegNext(async_idle_status))

  val timeout_counter = RegInit(0.U(log2Ceil(des_timeout_cycles).W))

  when(idle_status_sync) {
    timeout_counter := 0.U
  }.otherwise {    
    when(timeout_counter === des_timeout_cycles.U) {
      timeout_counter := des_timeout_cycles.U
    }.otherwise {
      timeout_counter := timeout_counter + 1.U  
    }  
  }

  io.ctrl.des_timedout := timeout_counter === des_timeout_cycles.U

  val valid_sync_prev = RegNext(valid_sync, false.B) 
  val dataSent = RegInit(false.B)

  val newData = valid_sync && !valid_sync_prev  // new data pulse

  when(newData) {
    dataSent := false.B
  }

  when(io.out.msg.fire) {
    dataSent := true.B   // mark as sent
  }

  io.out.msg.valid := valid_sync && !dataSent
  io.out.msg.bits := data_sync
}

object MainSBSerdes extends App {

  ChiselStage.emitSystemVerilogFile(
    new SidebandLinkSerDes(new SidebandParams()),
    args = Array("-td", "./generators/ucie/generatedVerilog/sideband"),
    firtoolOpts = Array(
      "-O=debug",
      "-g",
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    ),
  )
}
