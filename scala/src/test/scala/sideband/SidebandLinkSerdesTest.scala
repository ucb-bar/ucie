package edu.berkeley.cs.uciedigital.sideband

import chisel3._
import chisel3.util._

// Currently using ChiselTest because of ChiselSim issues in Chisel 6 (No waveforms)
// Ideally want to move to ChiselSim when switched to Chisel 7 (Improved ChiselSim)
import chiseltest._
import firrtl2.options.TargetDirAnnotation

// ChiselSim for Chisel 6
// import chisel3.simulator.EphemeralSimulator._    

// ChiselSim for Chisel 7.0+
// import chisel3.simulator.scalatest.ChiselSim     
// import chisel3.simulator.stimulus.{RunUntilFinished, RunUntilSuccess, ResetProcedure}

import org.scalatest.funspec.AnyFunSpec


class SidebandLinkSerdesTest extends AnyFunSpec with ChiselScalatestTester{

  val sbParams = new SidebandParams()
  val msg_w = sbParams.sbNodeMsgWidth
  val sbLink_w = sbParams.sbLinkWidth
  
  describe("Sideband Link Serializer and Deserializer Instantiation Test") {
    it("Instantiated Serializer") {
      test(new SidebandLinkSerializer(sbLink_w, msg_w)) { c =>
        c.clock.step()
        println("[TEST] Success")
      }
    }
     
    it("Instantiated Deserializer") { 
      val timeoutCycles = 512
      test(new SidebandLinkDeserializer(sbLink_w, msg_w, timeoutCycles)) { c =>
        c.clock.step()  
        println("[TEST] Success")
      }
    }
  }

  def toBits(value: BigInt, width: Int): String = {
    val binary = value.toString(2)
    "0" * (width - binary.length) + binary
  }

  def checkSerializedData(c: SidebandLinkSerializer, refData: UInt, len: Int): Unit = {
    /** This function checks the data out of the serializer as well
      * as making sure data and clock are low during the waits.
      * If message len is 128 bits then we want 64 data, 32 low, 64 data, 32 low.
      */
    val loopCount = len + ((len / 64) * 32)
    var serializedData = false.B
    var bitstring = ""

    for(i <- 0 until loopCount) {
      
      if((i >= 0 && i < 64)) {
        serializedData = refData(i)
      }
      else if (i >= 96 && i < 160) {
        serializedData = refData(i-32)
      }

      if((i >= 0 && i < 64) || (i >= 96 && i < 160)) { // data
        val bit = c.io.out.bits.peek().litValue
        bitstring += bit.toString
        c.io.out.bits.expect(serializedData)
      } 
      else if((i >= 64 && i < 96) || (i >= 160 && i < 192)) { // wait
        c.io.out.bits.expect(0.U)
        c.io.out.clock.expect(false.B)                      
      }

      c.io.in.msg.ready.expect(false.B)      
      c.clock.step()
    }

    c.io.out.bits.expect(0.U)
    c.io.out.clock.expect(false.B)
    c.io.in.msg.ready.expect(true.B)

    println(s"[TEST] Captured Bitstring:    ${bitstring.reverse}")
    println(s"[TEST] Number of bits serialized: ${bitstring.length}")
    println("[TEST] Success")
  }

  describe("Serialize RAW bits") {  
    val numPackets = 5
    it(s"Serialized ${numPackets} RAW packet(s) (64 bits per packet)") {
      test(new SidebandLinkSerializer(sbLink_w, msg_w))
      // .withAnnotations(Seq(WriteVcdAnnotation))
      // .withAnnotations(Seq(TargetDirAnnotation("./generators/ucie/src/test/test_run_dir/"))) 
      { c =>                  
        val waitCyclesCtrl = 5 // max of random wait cycles              
        val seed = 0
        val rand = new scala.util.Random(seed)            
        val bitWidth = 64 

        // initialize
        c.io.ctrl.tx_mode.poke(SBRxTxMode.RAW)
        c.io.in.msg.valid.poke(false.B)
        c.clock.step(10)
        c.io.out.bits.expect(0.U)
        c.io.out.clock.expect(false.B)
        
        for(i <- 0 until numPackets) {
          println(s"====== Sending packet ${i + 1} ======")
          val data = BigInt(bitWidth, rand)                          
          println("[TEST] Serializing (Binary):  " + toBits(data, bitWidth))      
          
          // Send data
          c.io.in.msg.ready.expect(true.B)
          c.io.in.msg.bits.poke(data.U)
          c.io.in.msg.valid.poke(true.B)           
          c.clock.step()
          c.io.in.msg.valid.poke(false.B)
        
          checkSerializedData(c, data.U, bitWidth)

          val waitAmt = rand.nextInt(waitCyclesCtrl)
          if(i != (numPackets - 1)) {
            println(s"[TEST] Waiting ${waitAmt} cycles before sending next message")
          }
          if (waitAmt != 0) {              
            c.clock.step(waitAmt) 
          }
          c.io.in.msg.ready.expect(true.B)
          c.io.out.bits.expect(0.U)
          c.io.out.clock.expect(false.B) 
        }                               
      }
    }
  }

  describe("Serialize 64-bit sideband packet(s)") {
    val numPackets = 5
    it(s"Serialized ${numPackets} 64-bit packet(s)") {
      test(new SidebandLinkSerializer(sbLink_w, msg_w))
      // .withAnnotations(Seq(WriteVcdAnnotation))
      // .withAnnotations(Seq(TargetDirAnnotation("./generators/ucie/src/test/test_run_dir/"))) 
      { c =>    
        val waitCyclesCtrl = 5 // max of random wait cycles
        val seed = 234
        val rand = new scala.util.Random(seed)
        val bitWidth = 64
        
        println(s"[TEST] Starting: Serializing ${numPackets} 64-bit packet(s)")        
        // initialize
        c.io.ctrl.tx_mode.poke(SBRxTxMode.PACKET)
        c.io.in.msg.valid.poke(false.B)
        c.clock.step(10)
        c.io.out.bits.expect(0.U)
        c.io.out.clock.expect(false.B)

        // Opcode will be valid but randomly selected. Remaining bits are random.        
        for(i <- 0 until numPackets) {
          println(s"====== Sending packet ${i + 1} ======")     

          val opcodes64Bits = Seq(SBMsgOpcode.MessageWithoutData, 
                                  SBMsgOpcode.ManagementPortMsgWithoutData)
          
          val randomOpcode = opcodes64Bits(rand.nextInt(opcodes64Bits.length))
          println("[TEST] Selected opcode: " + randomOpcode) 
          println("[TEST] Selected opcode bits:  " + toBits(randomOpcode.litValue, bitWidth))                                            
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          println("[TEST] Generated random bits: " + toBits(randData, bitWidth)) 
          val data = randData | randomOpcode.litValue
          println("[TEST] Serializing (Binary):  " + toBits(data, bitWidth))   
                      
          // Send data
          c.io.in.msg.ready.expect(true.B)  
          c.io.in.msg.bits.poke(data.U)
          c.io.in.msg.valid.poke(true.B)           
          c.clock.step()
          c.io.in.msg.valid.poke(false.B)
      
          checkSerializedData(c, data.U, bitWidth)

          val waitAmt = rand.nextInt(waitCyclesCtrl)
          if(i != (numPackets - 1)) {
            println(s"[TEST] Waiting ${waitAmt} cycles before sending next message")
          }                            
          if (waitAmt != 0) {              
            c.clock.step(waitAmt) 
          }
          c.io.in.msg.ready.expect(true.B)
          c.io.out.bits.expect(0.U)
          c.io.out.clock.expect(false.B)         
        }                               
      }
    } 
  }

  describe("Serialize 128-bit sideband packet(s)") {
    val numPackets = 10
    it(s"Serialized ${numPackets} 128-bit packet(s)") {
      test(new SidebandLinkSerializer(sbLink_w, msg_w))
      // .withAnnotations(Seq(WriteVcdAnnotation))
      // .withAnnotations(Seq(TargetDirAnnotation("./generators/ucie/src/test/test_run_dir/"))) 
      { c =>  
        val waitCyclesCtrl = 5 // max of random wait cycles
        val seed = 2352
        val rand = new scala.util.Random(seed)
        val bitWidth = 128
        
        println(s"[TEST] Starting: Serializing ${numPackets} 128-bit packet(s)")        
        // initialize
        c.io.ctrl.tx_mode.poke(SBRxTxMode.PACKET)
        c.io.in.msg.valid.poke(false.B)
        c.clock.step(10)
        c.io.out.bits.expect(0.U)
        c.io.out.clock.expect(false.B)

        // Opcode will be valid but randomly selected. Remaining bits are random.        
        for(i <- 0 until numPackets) {
          println(s"====== Sending packet ${i + 1} ======")     

          val opcodes64Bits = Seq(SBMsgOpcode.MessageWithoutData, 
                                  SBMsgOpcode.ManagementPortMsgWithoutData)
          
          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)
          
          val randomOpcode = opcodes128Bits(rand.nextInt(opcodes128Bits.length))
          println("[TEST] Selected opcode: " + randomOpcode) 
          println("[TEST] Selected opcode bits:  " + toBits(randomOpcode.litValue, bitWidth))                                            
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          println("[TEST] Generated random bits: " + toBits(randData, bitWidth)) 
          val data = randData | randomOpcode.litValue
          println("[TEST] Serializing (Binary):  " + toBits(data, bitWidth))   
                      
          // Send data
          c.io.in.msg.ready.expect(true.B)  
          c.io.in.msg.bits.poke(data.U)
          c.io.in.msg.valid.poke(true.B)           
          c.clock.step()
          c.io.in.msg.valid.poke(false.B)
      
          checkSerializedData(c, data.U, bitWidth)

          val waitAmt = rand.nextInt(waitCyclesCtrl)
          if(i != (numPackets - 1)) {
            println(s"[TEST] Waiting ${waitAmt} cycles before sending next message")
          }
          if(waitAmt != 0) {              
            c.clock.step(waitAmt) 
          }
          c.io.in.msg.ready.expect(true.B)
          c.io.out.bits.expect(0.U)
          c.io.out.clock.expect(false.B)         
        }                               
      }
    } 
  }

  describe("Serialize any sideband packet(s)") {
    val numPackets = 10
    it(s"Serialized ${numPackets} sideband packet(s)") {
      test(new SidebandLinkSerializer(sbLink_w, msg_w))
      // .withAnnotations(Seq(WriteVcdAnnotation))
      // .withAnnotations(Seq(TargetDirAnnotation("./generators/ucie/src/test/test_run_dir/"))) 
      { c =>    
        val waitCyclesCtrl = 5  // max of random wait cycles
        val opcodeRandCtrl = 8  // max of random opcode select
        val seed = 979
        val rand = new scala.util.Random(seed)
        
        println(s"[TEST] Starting: Serializing ${numPackets} sideband packet(s)")        
        // initialize
        c.io.ctrl.tx_mode.poke(SBRxTxMode.PACKET)
        c.io.in.msg.valid.poke(false.B)
        c.clock.step(10)
        c.io.out.bits.expect(0.U)
        c.io.out.clock.expect(false.B)

        // Opcode will be valid but randomly selected. Remaining bits are random.        
        for(i <- 0 until numPackets) {
          println(s"====== Sending packet ${i + 1} ======")     

          val opcodes64Bits = Seq(SBMsgOpcode.MessageWithoutData, 
                                  SBMsgOpcode.ManagementPortMsgWithoutData)
          
          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)
                    
          val selectOpcode = rand.nextInt(opcodeRandCtrl) // select between 64 and 128
          val (randomOpcode, bitWidth) = {
              if(selectOpcode < (opcodeRandCtrl / 2)) {
                (opcodes128Bits(rand.nextInt(opcodes128Bits.length)), 128)
            } else {
                (opcodes64Bits(rand.nextInt(opcodes64Bits.length)), 64)
            }
          }

          println("[TEST] Selected opcode: " + randomOpcode) 
          println("[TEST] Selected opcode bits:  " + toBits(randomOpcode.litValue, bitWidth))                                            
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          println("[TEST] Generated random bits: " + toBits(randData, bitWidth)) 
          val data = randData | randomOpcode.litValue
          println("[TEST] Serializing (Binary):  " + toBits(data, bitWidth))   
                      
          // Send data
          c.io.in.msg.ready.expect(true.B)  
          c.io.in.msg.bits.poke(data.U)
          c.io.in.msg.valid.poke(true.B)           
          c.clock.step()
          c.io.in.msg.valid.poke(false.B)
      
          checkSerializedData(c, data.U, bitWidth)

          val waitAmt = rand.nextInt(waitCyclesCtrl)
          if(i != (numPackets - 1)) {
            println(s"[TEST] Waiting ${waitAmt} cycles before sending next message")
          }
          if(waitAmt != 0) {              
            c.clock.step(waitAmt) 
          }
          c.io.in.msg.ready.expect(true.B)
          c.io.out.bits.expect(0.U)
          c.io.out.clock.expect(false.B)         
        }                               
      }
    } 
  }

  
  describe("Serialize a packet but reset is triggered in the middle") {
    val numPackets = 5
    it(s"Stopped serializing a packet when reset was triggered") {
      test(new SidebandLinkSerializer(sbLink_w, msg_w)) { c =>  
        val waitCyclesCtrl = 5  // max of random wait cycles before next run
        val opcodeRandCtrl = 8  // max of random opcode select
        val seed = 979
        val rand = new scala.util.Random(seed)
        
        println(s"[TEST] Starting: Serializing sideband packet(s)")        
        // initialize
        c.io.ctrl.tx_mode.poke(SBRxTxMode.PACKET)
        c.io.in.msg.valid.poke(false.B)
        c.clock.step(10)
        c.io.out.bits.expect(0.U)
        c.io.out.clock.expect(false.B)

        // Opcode will be valid but randomly selected. Remaining bits are random.        
        for(i <- 0 until numPackets) {
          println(s"====== Sending packet ${i + 1} ======")     

          val opcodes64Bits = Seq(SBMsgOpcode.MessageWithoutData, 
                                  SBMsgOpcode.ManagementPortMsgWithoutData)
          
          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)
                    
          val selectOpcode = rand.nextInt(opcodeRandCtrl) // select between 64 and 128
          val (randomOpcode, bitWidth) = {
              if(selectOpcode < (opcodeRandCtrl / 2)) {
                (opcodes128Bits(rand.nextInt(opcodes128Bits.length)), 128)
            } else {
                (opcodes64Bits(rand.nextInt(opcodes64Bits.length)), 64)
            }
          }

          println("[TEST] Selected opcode: " + randomOpcode) 
          println("[TEST] Selected opcode bits:  " + toBits(randomOpcode.litValue, bitWidth))                                            
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          println("[TEST] Generated random bits: " + toBits(randData, bitWidth)) 
          val data = randData | randomOpcode.litValue
          println("[TEST] Serializing (Binary):  " + toBits(data, bitWidth))   
                      
          // Send data
          c.io.in.msg.ready.expect(true.B)  
          c.io.in.msg.bits.poke(data.U)
          c.io.in.msg.valid.poke(true.B)           
          c.clock.step()
          c.io.in.msg.valid.poke(false.B)

          // wait a random amount between 0 and size of the packet before triggering reset 
          // NOTE: When it serializes messages it can should interrupt in wait as well
          //       the captured bit string will have string of 0s if it interrupts in the wait 
          val resetWaitAmt = rand.nextInt(bitWidth + 32)
          println(s"[TEST] Waiting ${resetWaitAmt} cycles before triggering reset")
          var bitstring = ""
          for(i <- 0 until resetWaitAmt) {
            val bit = c.io.out.bits.peek().litValue
            bitstring += bit.toString   
            c.io.in.msg.ready.expect(false.B) 
            c.clock.step()        
          }

          println(s"[TEST] Captured Bitstring:    ${bitstring.reverse}")
          println(s"[TEST] Number of bits serialized: ${bitstring.length}")

          c.reset.poke(true.B)
          c.clock.step(10)
          c.reset.poke(false.B)

          c.io.in.msg.ready.expect(true.B)
          c.io.out.bits.expect(0.U)
          c.io.out.clock.expect(false.B) 

          val waitAmt = rand.nextInt(waitCyclesCtrl)                
          if(waitAmt != 0) {              
            c.clock.step(waitAmt) 
          }
          c.io.in.msg.ready.expect(true.B)
          c.io.out.bits.expect(0.U)
          c.io.out.clock.expect(false.B)         
        }                               
      }
    } 
  }

  def serializeData(c: DeserializerTestHarness, refData: UInt, len: Int, 
                    resetCyclesTarget: Int = -1, timeoutCyclesTarget: Int = -1): Unit = {

    val totalBits = len + ((len / 64) * 32)
    var resetWaitCount = 0
    var timeoutWaitCount = 0
    for(i <- 0 until totalBits) {

      // reset test triggers reset at target cycles, and exits the function
      resetWaitCount += 1
      if(resetWaitCount == resetCyclesTarget) {   // -1 is used as no test
        c.reset.poke(true.B) 
        c.clock.step(2)                         
        return
      }

      // will stop serialzing randomly during the data or the wait
      timeoutWaitCount += 1
      if(timeoutWaitCount == timeoutCyclesTarget) { // -1 is used as no test
        c.io.in.bits.poke(0.U)
        c.io.in.fw_clock.poke(false.B) 
        return
      }

      // serialize data
      if((i >= 0 && i < 64) || (i >= 96 && i < 160)) { // data
        val bitIdx = { if(i >= 96 && i < 160) { i - 32 } else { i } }              
        c.io.in.bits.poke(refData(bitIdx))
        c.io.in.fw_clock.poke(true.B)
        c.io.out.msg.valid.expect(false.B)
      } 
      else if((i >= 64 && i < 96) || (i >= 160 && i < 192)) { // wait
        c.io.in.bits.poke(0.U)
        c.io.in.fw_clock.poke(false.B) 
      }
      
      c.clock.step()
    }
  }

  class DeserializerTestHarness(sbLink_w: Int, msg_w: Int, timeoutCycles: Int) extends Module {
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

    val dut = Module(new SidebandLinkDeserializer(sbLink_w, msg_w, timeoutCycles))

    dut.io.ctrl <> io.ctrl
    dut.io.out <> io.out
    dut.io.in.bits := io.in.bits
    dut.io.in.fw_clock := (Mux(io.in.fw_clock, clock.asBool, false.B))
  }

  describe("Deserialize a RAW packet(s) (64 bits per packet)") {
    val timeoutCycles = 512
    val numPackets = 5 
    it(s"Deserialized ${numPackets} RAW packet(s) (64 bits per packet)") {
      test((new DeserializerTestHarness(sbLink_w, msg_w, timeoutCycles)))
        // .withAnnotations(Seq(VerilatorBackendAnnotation))
        // .withAnnotations(Seq(VcsBackendAnnotation))
        // .withAnnotations(Seq(WriteFsdbAnnotation))
        // .withAnnotations(Seq(TargetDirAnnotation("./generators/ucie/src/test/test_run_dir/")))
        // .withAnnotations(Seq(WriteVcdAnnotation)) 
      { c => 
        val seed = 0
        val rand = new scala.util.Random(seed)
        val bitWidth = 64

        for(i <- 0 until numPackets) {
          // initialize
          c.io.ctrl.rx_mode.poke(SBRxTxMode.RAW)
          c.io.in.fw_clock.poke(false.B)
          c.io.out.msg.ready.poke(false.B)

          for(i <- 0 until 50) {
            c.io.out.msg.valid.expect(false.B) 
            c.clock.step()
          }

          println(s"====== Sending packet ${i + 1} ======")
          val data = BigInt(bitWidth, rand)                          
          println("[TEST] Deserializing (Binary): " + toBits(data, bitWidth))

          serializeData(c, data.U, bitWidth)

          // check data
          c.clock.step()
          c.io.out.msg.valid.expect(true.B)
          val capturedData = 
               c.io.out.msg.bits.peek().litValue.toString(2).reverse.padTo(bitWidth, '0').reverse
          
          c.io.out.msg.ready.poke(true.B)
          c.clock.step(2)
          c.io.out.msg.valid.expect(false.B) // valid should toggle low once data accepted

          println("[TEST] Captured Bits (Binary): " + capturedData)       
          println(s"[TEST] Number of bits captured: ${capturedData.length}")

          assert(capturedData == toBits(data, bitWidth), s"[TEST] Error in deserialization")
          println("[TEST] Success")
        }
      }
    }
  }

  describe("Deserialize a sideband packet(s) (64 bits per packet)") {
    val timeoutCycles = 512
    val numPackets = 5 
    it(s"Deserialized ${numPackets} sideband packet(s) (64 bits per packet)") {
      test((new DeserializerTestHarness(sbLink_w, msg_w, timeoutCycles)))
        // .withAnnotations(Seq(VerilatorBackendAnnotation))
        // .withAnnotations(Seq(VcsBackendAnnotation))
        // .withAnnotations(Seq(WriteFsdbAnnotation))
        // .withAnnotations(Seq(TargetDirAnnotation("./generators/ucie/src/test/test_run_dir/")))
        // .withAnnotations(Seq(WriteVcdAnnotation)) 
      { c => 
        val seed = 0
        val rand = new scala.util.Random(seed)
        val bitWidth = 64

        for(i <- 0 until numPackets) {
          // initialize
          c.io.ctrl.rx_mode.poke(SBRxTxMode.PACKET)
          c.io.in.fw_clock.poke(false.B)
          c.io.out.msg.ready.poke(false.B)

          for(i <- 0 until 50) {
            c.io.out.msg.valid.expect(false.B) 
            c.clock.step()
          }
          println(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = Seq(SBMsgOpcode.MessageWithoutData, 
                                  SBMsgOpcode.ManagementPortMsgWithoutData)
          
          val randomOpcode = opcodes64Bits(rand.nextInt(opcodes64Bits.length))
          println("[TEST] Selected opcode: " + randomOpcode) 
          println("[TEST] Selected opcode bits:   " + toBits(randomOpcode.litValue, bitWidth))                                            
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          println("[TEST] Generated random bits:  " + toBits(randData, bitWidth)) 
          val data = randData | randomOpcode.litValue

          println("[TEST] Deserializing (Binary): " + toBits(data, bitWidth))

          serializeData(c, data.U, bitWidth)

          // check data
          c.clock.step()
          c.io.out.msg.valid.expect(true.B)
          val capturedData = 
               c.io.out.msg.bits.peek().litValue.toString(2).reverse.padTo(bitWidth, '0').reverse
          
          c.io.out.msg.ready.poke(true.B)
          c.clock.step(2)
          c.io.out.msg.valid.expect(false.B) // valid should toggle low once data accepted

          println("[TEST] Captured Bits (Binary): " + capturedData)       
          println(s"[TEST] Number of bits captured: ${capturedData.length}")

          assert(capturedData == toBits(data, bitWidth), s"[TEST] Error in deserialization")
          println("[TEST] Success")
        }
      }
    }
  }

  describe("Deserialize a sideband packet(s) (128 bits per packet)") {
    val timeoutCycles = 512
    val numPackets = 5 
    it(s"Deserialized ${numPackets} sideband packet(s) (64 bits per packet)") {
      test((new DeserializerTestHarness(sbLink_w, msg_w, timeoutCycles)))
        // .withAnnotations(Seq(VerilatorBackendAnnotation))
        // .withAnnotations(Seq(VcsBackendAnnotation))
        // .withAnnotations(Seq(WriteFsdbAnnotation))
        // .withAnnotations(Seq(TargetDirAnnotation("./generators/ucie/src/test/test_run_dir/")))
        // .withAnnotations(Seq(WriteVcdAnnotation)) 
      { c => 
        val seed = 0
        val rand = new scala.util.Random(seed)
        val bitWidth = 128

        for(i <- 0 until numPackets) {
          // initialize
          c.io.ctrl.rx_mode.poke(SBRxTxMode.PACKET)
          c.io.in.fw_clock.poke(false.B)
          c.io.out.msg.ready.poke(false.B)

          for(i <- 0 until 50) {
            c.io.out.msg.valid.expect(false.B) 
            c.clock.step()
          }
          println(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = Seq(SBMsgOpcode.MessageWithoutData, 
                                            SBMsgOpcode.ManagementPortMsgWithoutData)
          
          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)
          
          val randomOpcode = opcodes128Bits(rand.nextInt(opcodes128Bits.length))

          println("[TEST] Selected opcode: " + randomOpcode) 
          println("[TEST] Selected opcode bits:   " + toBits(randomOpcode.litValue, bitWidth))                                            
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          println("[TEST] Generated random bits:  " + toBits(randData, bitWidth)) 
          val data = randData | randomOpcode.litValue

          println("[TEST] Deserializing (Binary): " + toBits(data, bitWidth))

          serializeData(c, data.U, bitWidth)

          // check data
          c.clock.step()
          c.io.out.msg.valid.expect(true.B)
          val capturedData = 
               c.io.out.msg.bits.peek().litValue.toString(2).reverse.padTo(bitWidth, '0').reverse
          
          c.io.out.msg.ready.poke(true.B)
          c.clock.step(2)
          c.io.out.msg.valid.expect(false.B) // valid should toggle low once data accepted

          println("[TEST] Captured Bits (Binary): " + capturedData)       
          println(s"[TEST] Number of bits captured: ${capturedData.length}")

          assert(capturedData == toBits(data, bitWidth), s"[TEST] Error in deserialization")
          println("[TEST] Success")
        }
      }
    }
  }

  describe("Deserialize any sideband packet(s)") {
    val timeoutCycles = 512
    val numPackets = 5 
    val opcodeRandCtrl = 8  // max of random opcode select

    it(s"Deserialized ${numPackets} sideband packet(s)") {
      test((new DeserializerTestHarness(sbLink_w, msg_w, timeoutCycles)))
        // .withAnnotations(Seq(VerilatorBackendAnnotation))
        // .withAnnotations(Seq(VcsBackendAnnotation))
        // .withAnnotations(Seq(WriteFsdbAnnotation))
        // .withAnnotations(Seq(TargetDirAnnotation("./generators/ucie/src/test/test_run_dir/")))
        // .withAnnotations(Seq(WriteVcdAnnotation)) 
      { c => 
        val seed = 0
        val rand = new scala.util.Random(seed)

        for(i <- 0 until numPackets) {
          // initialize
          c.io.ctrl.rx_mode.poke(SBRxTxMode.PACKET)
          c.io.in.fw_clock.poke(false.B)
          c.io.out.msg.ready.poke(false.B)

          for(i <- 0 until 50) {
            c.io.out.msg.valid.expect(false.B) 
            c.clock.step()
          }
          println(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = Seq(SBMsgOpcode.MessageWithoutData, 
                                  SBMsgOpcode.ManagementPortMsgWithoutData)

          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)
                    
          val selectOpcode = rand.nextInt(opcodeRandCtrl) // select between 64 and 128
          val (randomOpcode, bitWidth) = {
              if(selectOpcode < (opcodeRandCtrl / 2)) {
                (opcodes128Bits(rand.nextInt(opcodes128Bits.length)), 128)
            } else {
                (opcodes64Bits(rand.nextInt(opcodes64Bits.length)), 64)
            }
          }
          
          println("[TEST] Selected opcode: " + randomOpcode) 
          println("[TEST] Selected opcode bits:   " + toBits(randomOpcode.litValue, bitWidth))
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          println("[TEST] Generated random bits:  " + toBits(randData, bitWidth)) 
          val data = randData | randomOpcode.litValue

          println("[TEST] Deserializing (Binary): " + toBits(data, bitWidth))

          serializeData(c, data.U, bitWidth)

          // check data
          c.clock.step()
          c.io.out.msg.valid.expect(true.B)
          val fullStr = 
               c.io.out.msg.bits.peek().litValue.toString(2).reverse.padTo(bitWidth, '0').reverse
          val capturedData = { if(bitWidth == 64) { fullStr.takeRight(64) } else { fullStr }}
          
          c.io.out.msg.ready.poke(true.B)
          c.clock.step(2)
          c.io.out.msg.valid.expect(false.B) // valid should toggle low once data accepted

          println("[TEST] Captured Bits (Binary): " + capturedData)       
          println(s"[TEST] Number of bits captured: ${capturedData.length}")

          assert(capturedData == toBits(data, bitWidth), s"[TEST] Error in deserialization")
          println("[TEST] Success")
        }
      }
    }
  }

  describe("Deserialize a packet but reset is triggered in the middle") {
    val timeoutCycles = 512
    val numPackets = 5 
    val opcodeRandCtrl = 8  // max of random opcode select

    it(s"Stopped serializing a packet when reset was triggered") {
      test((new DeserializerTestHarness(sbLink_w, msg_w, timeoutCycles)))
        // .withAnnotations(Seq(VerilatorBackendAnnotation))
        // .withAnnotations(Seq(VcsBackendAnnotation))
        // .withAnnotations(Seq(WriteFsdbAnnotation))
        // .withAnnotations(Seq(TargetDirAnnotation("./generators/ucie/src/test/test_run_dir/")))
        // .withAnnotations(Seq(WriteVcdAnnotation)) 
      { c => 
        val seed = 0
        val rand = new scala.util.Random(seed)

        for(i <- 0 until numPackets) {
          // initialize
          c.io.ctrl.rx_mode.poke(SBRxTxMode.PACKET)
          c.io.in.fw_clock.poke(false.B)
          c.io.out.msg.ready.poke(false.B)

          for(i <- 0 until 50) {
            c.io.out.msg.valid.expect(false.B) 
            c.clock.step()
          }
          println(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = Seq(SBMsgOpcode.MessageWithoutData, 
                                  SBMsgOpcode.ManagementPortMsgWithoutData)

          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)
                    
          val selectOpcode = rand.nextInt(opcodeRandCtrl) // select between 64 and 128
          val (randomOpcode, bitWidth) = {
              if(selectOpcode < (opcodeRandCtrl / 2)) {
                (opcodes128Bits(rand.nextInt(opcodes128Bits.length)), 128)
            } else {
                (opcodes64Bits(rand.nextInt(opcodes64Bits.length)), 64)
            }
          }
          
          println("[TEST] Selected opcode: " + randomOpcode) 
          println("[TEST] Selected opcode bits:   " + toBits(randomOpcode.litValue, bitWidth))
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          println("[TEST] Generated random bits:  " + toBits(randData, bitWidth)) 
          val data = randData | randomOpcode.litValue

          println("[TEST] Deserializing (Binary): " + toBits(data, bitWidth))

          val resetWaitAmt = rand.nextInt(bitWidth + 31) + 1

          println("[TEST] Number of cycles before triggering reset: " + resetWaitAmt)

          serializeData(c, data.U, bitWidth, resetCyclesTarget = resetWaitAmt)

          // check data
          /*
            Note if the forwarded clock keeps running then deserializer would sample
            after reset, but whole ucie would reset, and won't be respondeding to the partner, so
            the partner will timeout
          */
          for(i <- 0 until 50) {
            c.io.out.msg.bits.expect(0.U, "Should output 0")
            c.io.out.msg.valid.expect(false.B)
            c.clock.step()                           
          }                          
          val capturedData = c.io.out.msg.bits.peek().litValue           
          println("[TEST] Captured Data (Binary): " + toBits(capturedData, bitWidth))
          assert(capturedData == 0, s"[TEST] Error in reset")
          println("[TEST] Success")
          c.reset.poke(false.B)
        }
      }
    }
  }

  describe("Deserializer signals a timeout when the serializer stops sending a packet") {
    val timeoutCycles = 1000
    val numPackets = 5 
    val opcodeRandCtrl = 8  // max of random opcode select

    it(s"Triggered a timeout when serializer stopped sending a packet") {
      test((new DeserializerTestHarness(sbLink_w, msg_w, timeoutCycles)))
        // .withAnnotations(Seq(VerilatorBackendAnnotation))
        // .withAnnotations(Seq(VcsBackendAnnotation))
        // .withAnnotations(Seq(WriteFsdbAnnotation))
        // .withAnnotations(Seq(TargetDirAnnotation("./generators/ucie/src/test/test_run_dir/")))
        // .withAnnotations(Seq(WriteVcdAnnotation)) 
      { c => 
        val seed = 0
        val rand = new scala.util.Random(seed)
        c.clock.setTimeout(0)
        for(i <- 0 until numPackets) {          
          // initialize
          c.io.ctrl.rx_mode.poke(SBRxTxMode.PACKET)
          c.io.in.fw_clock.poke(false.B)
          c.io.out.msg.ready.poke(false.B)

          for(i <- 0 until 50) {
            c.io.out.msg.valid.expect(false.B) 
            c.clock.step()
          }
          println(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = Seq(SBMsgOpcode.MessageWithoutData, 
                                  SBMsgOpcode.ManagementPortMsgWithoutData)

          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)
                    
          val selectOpcode = rand.nextInt(opcodeRandCtrl) // select between 64 and 128
          val (randomOpcode, bitWidth) = {
              if(selectOpcode < (opcodeRandCtrl / 2)) {
                (opcodes128Bits(rand.nextInt(opcodes128Bits.length)), 128)
            } else {
                (opcodes64Bits(rand.nextInt(opcodes64Bits.length)), 64)
            }
          }
          
          println("[TEST] Selected opcode: " + randomOpcode) 
          println("[TEST] Selected opcode bits:   " + toBits(randomOpcode.litValue, bitWidth))
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          println("[TEST] Generated random bits:  " + toBits(randData, bitWidth)) 
          val data = randData | randomOpcode.litValue

          println("[TEST] Deserializing (Binary): " + toBits(data, bitWidth))

          val timeoutWaitAmt = rand.nextInt(bitWidth + 31) + 1

          println("[TEST] Number of cycles before killing serializer: " + timeoutWaitAmt)

          serializeData(c, data.U, bitWidth, timeoutCyclesTarget = timeoutWaitAmt)

          // check timeout signal
          c.clock.step(timeoutCycles)                           
          c.io.ctrl.des_timedout.expect(true.B, "Should've triggered timeout")
          println("[TEST] Successfully triggered a timeout")

          // Asssuming once a timeout is triggered you'd reset everything because BER for sideband
          // is 1e-27 or better, so spec doesn't have a retry mechanism on SB messages
          c.reset.poke(true.B)
          c.clock.step(5)
          c.reset.poke(false.B)

        }
      }
    }
  }

  class LinkSerdesTestHarness extends Module {
    val io = IO(new Bundle {
      val ctrl = new Bundle {
        val rxtx_mode = Input(SBRxTxMode())
        val des_timedout = Output(Bool())
      }        
      val serializerIO = new Bundle {
        val in = new Bundle {
          val msg = Flipped(Decoupled(UInt(msg_w.W)))
        }            
      }
      val deserializerIO = new Bundle {
        val out = new Bundle {
          val msg = Decoupled(UInt(msg_w.W))
        } 
      }
    })    

    val dut = Module(new SidebandLinkSerDes(new SidebandParams()))

    dut.io.serializerIO.out <> dut.io.deserializerIO.in
    dut.io.serializerIO.in <> io.serializerIO.in
    dut.io.deserializerIO.out <> io.deserializerIO.out
    dut.io.ctrl.sb_clock := clock
    dut.io.ctrl.rxtx_mode := io.ctrl.rxtx_mode
    io.ctrl.des_timedout := dut.io.ctrl.des_timedout 
  }

  describe("Sanity test using async queues and a loopback configuration") {
    val timeoutCycles = 1000
    val numPackets = 5
    val opcodeRandCtrl = 8  // max of random opcode select

    it(s"Serialized and deserialized single packets in loopback through async queues") {
      test((new LinkSerdesTestHarness))
        // .withAnnotations(Seq(VerilatorBackendAnnotation))
        // .withAnnotations(Seq(VcsBackendAnnotation))
        // .withAnnotations(Seq(WriteFsdbAnnotation))
        // .withAnnotations(Seq(TargetDirAnnotation("./generators/ucie/src/test/test_run_dir/")))
        // .withAnnotations(Seq(WriteVcdAnnotation)) 
      { c => 
        val seed = 0
        val rand = new scala.util.Random(seed)
        c.clock.setTimeout(0)
        for(i <- 0 until numPackets) {          
          // initialize
          c.io.ctrl.rxtx_mode.poke(SBRxTxMode.PACKET)
          c.io.serializerIO.in.msg.valid.poke(false.B)
          c.io.deserializerIO.out.msg.ready.poke(false.B)

          for(i <- 0 until 50) {
            c.io.deserializerIO.out.msg.valid.expect(false.B) 
            c.clock.step()
          }
          println(s"====== Sending packet ${i + 1} ======")

          val opcodes64Bits = Seq(SBMsgOpcode.MessageWithoutData, 
                                  SBMsgOpcode.ManagementPortMsgWithoutData)

          val opcodes128Bits = SBMsgOpcode.all.diff(opcodes64Bits)
                    
          val selectOpcode = rand.nextInt(opcodeRandCtrl) // select between 64 and 128
          val (randomOpcode, bitWidth) = {
              if(selectOpcode < (opcodeRandCtrl / 2)) {
                (opcodes128Bits(rand.nextInt(opcodes128Bits.length)), 128)
            } else {
                (opcodes64Bits(rand.nextInt(opcodes64Bits.length)), 64)
            }
          }
          
          println("[TEST] Selected opcode: " + randomOpcode) 
          println("[TEST] Selected opcode bits:   " + toBits(randomOpcode.litValue, bitWidth))
          val randData = (BigInt(bitWidth - 5, rand)) << 5
          println("[TEST] Generated random bits:  " + toBits(randData, bitWidth)) 
          val data = randData | randomOpcode.litValue

          println("[TEST] Serializing (Binary):   " + toBits(data, bitWidth))

          c.io.serializerIO.in.msg.ready.expect(true.B)  
          c.io.serializerIO.in.msg.bits.poke(data.U)
          c.io.serializerIO.in.msg.valid.poke(true.B)           
          c.clock.step()
          c.io.serializerIO.in.msg.valid.poke(false.B)
          
          while(c.io.deserializerIO.out.msg.valid.peek().litToBoolean == false) {
            c.clock.step()
          }

          c.io.deserializerIO.out.msg.valid.expect(true.B)
          val fullStr = 
               c.io.deserializerIO.out.msg.bits.peek().litValue.toString(2)
                                                      .reverse
                                                      .padTo(bitWidth, '0')
                                                      .reverse

          val capturedData = { if(bitWidth == 64) { fullStr.takeRight(64) } else { fullStr }}
          
          c.io.deserializerIO.out.msg.ready.poke(true.B)
          c.clock.step(2)
          
          // valid should toggle low once data accepted
          c.io.deserializerIO.out.msg.valid.expect(false.B) 

          println("[TEST] Captured Bits (Binary): " + capturedData)       
          println(s"[TEST] Number of bits captured: ${capturedData.length}")

          assert(capturedData == toBits(data, bitWidth), s"[TEST] Error in deserialization")
          println("[TEST] Success")

        }
      }
    }
  }
}