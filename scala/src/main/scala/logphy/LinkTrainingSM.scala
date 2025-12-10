package edu.berkeley.cs.uciedigital.logphy

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._
import chisel3._
import chisel3.util._


case class AfeParams(
  sbSerializerRatio: Int = 1,
  sbWidth: Int = 1,
  mbSerializerRatio: Int = 32,
  mbLanes: Int = 16,
  STANDALONE: Boolean = true
)

object TimeoutConstants {
  def timeoutMap(mbSerializerRatio: Int, timeoutMs: Double = 0.008): Map[SpeedMode.Type, BigInt] = {

    // GT/s is divide by 2 because operating at DDR (half-rate clocking)
    Map(
      SpeedMode.speed4  -> (((2_000_000_000L / mbSerializerRatio) * timeoutMs).toLong),
      SpeedMode.speed8  -> (((4_000_000_000L / mbSerializerRatio) * timeoutMs).toLong),
      SpeedMode.speed12 -> (((6_000_000_000L / mbSerializerRatio) * timeoutMs).toLong),
      SpeedMode.speed16 -> (((8_000_000_000L / mbSerializerRatio) * timeoutMs).toLong),
      SpeedMode.speed24 -> (((12_000_000_000L / mbSerializerRatio) * timeoutMs).toLong),
      SpeedMode.speed32 -> (((16_000_000_000L / mbSerializerRatio) * timeoutMs).toLong),
      SpeedMode.speed48 -> (((24_000_000_000L / mbSerializerRatio) * timeoutMs).toLong),
      SpeedMode.speed64 -> (((32_000_000_000L / mbSerializerRatio) * timeoutMs).toLong)
    )
  }
}

object MBRxTxMode extends ChiselEnum {
  // Either send/receive RAW or process with valid framing
  val RAW, VALID_FRAME = Value
}

object MsgSource extends ChiselEnum {
  val PATTERN_GENERATOR, SB_MSG = Value
}

// BEGIN: Bundles
class SidebandCtrlIO extends Bundle {
  val txEn        = Output(Bool())
  val rxEn        = Output(Bool())
  val rxTxMode    = Output(SBRxTxMode())
  val sbSerDesRst = Output(Bool()) // TODO: Planning to toggle this high during the 4ms wait coming into reset
  val sbPllLock   = Input(Bool())    
}

class MainbandCtrlIO(afeParams: AfeParams) extends Bundle {
  val txDataTriState    = Output(Vec(afeParams.mbLanes, Bool()))
  val txClkTriState     = Output(Bool())
  val txValidTriState   = Output(Bool())
  val txTrackTriState   = Output(Bool())            
  val rxDataEn          = Output(Vec(afeParams.mbLanes, Bool()))
  val rxClkEn           = Output(Bool())
  val rxValidEn         = Output(Bool())
  val rxTrackEn         = Output(Bool())
  val freqSel           = Output(SpeedMode())    
  val rxTxMode          = Output(MBRxTxMode())  
  val mbPllLock         = Input(Bool())
}

class SidebandLanes(sbMsgWidth: Int) extends Bundle {
  /*
    For internal logPHY IOs. As of UCIe 3.0, we don't use the sideband
    clock besides in the deserializer, so we don't include it here.
  */
  val data = Bits(sbMsgWidth.W)
}

class SidebandLaneIO(sbParams: SidebandParams) extends Bundle {  
  val tx = Decoupled(new SidebandLanes(sbParams.sbNodeMsgWidth))
  val rx = Flipped(Decoupled(new SidebandLanes(sbParams.sbNodeMsgWidth)))
}

class MainbandLanes(mbNumLanes: Int, mbSerializerRatio: Int) extends Bundle {
  val data    = Vec(mbNumLanes, Bits(mbSerializerRatio.W))
  val valid   = Bits(mbSerializerRatio.W)
  val clkP    = Bits(mbSerializerRatio.W)
  val clkN    = Bits(mbSerializerRatio.W)
  val trk     = Bits(mbSerializerRatio.W)
}

class MainbandLaneIO(afeParams: AfeParams) extends Bundle {
  val tx = Decoupled(
    new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio))
  val rx = Flipped(Decoupled(
    new MainbandLanes(afeParams.mbLanes, afeParams.mbSerializerRatio)))
}

class SubFsmControlIO extends Bundle {
  val start = Input(Bool())
  val substateTransitioning = Output(Bool())
  val error = Output(Bool())
  val done = Output(Bool())
}
// END: Bundles

object LTState extends ChiselEnum {
  val sRESET, sSBINIT, sMBINIT, sMBTRAIN, sLINKINIT, sACTIVE, sPHYRETRAIN, sTRAINERROR, sL1_L2  
  = Value
}


class LinkTrainingSM(sbParams: SidebandParams, afeParams: AfeParams, retryW: Int) extends Module {

  // Variables
  val mbSerializerRatio = afeParams.mbSerializerRatio
  val timeoutMs = 0.008
  val retryAmtW = retryW  // TODO: Need to put retryW into a class

  val io = IO(new Bundle {
    val currentState = Output(LTState())  // Out to logphytop    
    val retryTrainingAmt = Input(UInt(retryAmtW.W))  // comes from ucie dvsec (controller in logphy)
    
    // NOTE: swTrigger from DVSEC regs, rdiTrigger from adapter 
    //       get both signals from logPHY controller
    val swTriggerTraining = Input(Bool())   // from dvsec regs (controller in logphy)     
    val rdiTriggerTraining = Input(Bool())  // from adapter (controller in logphy)

    val trainingBypass = Input(Bool())
    val selectStateBypass = Input(LTState())

    val trainingTimedout = Output(Bool())

    val pwrGood = Input(Bool())

    val sidebandCtrlIo = new SidebandCtrlIO()
    val mainbandCtrlIo = new MainbandCtrlIO(afeParams)
    val sidebandLaneIo = new SidebandLaneIO(sbParams)
    val mainbandLaneIo = new MainbandLaneIO(afeParams)
  })

  // Modules Instantiations
    // PatternWriter
    // PatternReader
  
    
  
  val currentState = RegInit(LTState.sRESET)
  val nextState = WireInit(currentState)
  val triggerTraining = Wire(Bool())
  


  // Timeout Logic -- Digital operates with divided mb clock to keep clock crossing at boundaries
  val timeoutMapScala = TimeoutConstants.timeoutMap(mbSerializerRatio, timeoutMs)
  val timeoutWidth = log2Ceil(timeoutMapScala.values.max)
  val timeoutMapChisel: Map[SpeedMode.Type, UInt] = timeoutMapScala.map { case (mode, big) =>
                                                      mode -> big.U(timeoutWidth.W)
                                                    }  
  val timeoutCounter = RegInit(0.U(timeoutWidth.W))
  val timeoutCyclesMax = Wire(UInt(timeoutWidth.W))
  val timeoutCntEn = Wire(Bool())       // disable next cycle
  val timeoutCntReset = Wire(Bool())    // reset next cycle
  val trainingTimedout = Wire(Bool())  
  val resetMinWait = RegInit(false.B)    
  val substateTransitioning = Wire(Bool())

  timeoutCntEn := (currentState =/= LTState.sRESET) &&
                  (currentState =/= LTState.sACTIVE) &&
                  (currentState =/= LTState.sL1_L2) &&
                  (currentState =/= LTState.sTRAINERROR)

  substateTransitioning := false.B
  timeoutCntReset := (nextState =/= currentState) || substateTransitioning
  trainingTimedout := timeoutCounter === timeoutCyclesMax
  

  // get correct timeout cycles based on PHY speed
  timeoutCyclesMax := MuxLookup(io.mainbandCtrlIo.freqSel, 
                                (timeoutMapScala.values.min - 1).U)(timeoutMapChisel.toSeq)
  when(timeoutCntReset) {
    timeoutCounter := 0.U
  }.otherwise {
    when(timeoutCntEn){
      when(timeoutCounter =/= timeoutCyclesMax) {
        timeoutCounter := timeoutCounter + 1.U               
      }
    }
  }

  // wait a minimum of 4ms upon entering RESET   
  when(timeoutCounter === (timeoutCyclesMax >> 2) && (currentState === LTState.sRESET)) {
    resetMinWait := true.B        
  }.elsewhen((currentState =/= LTState.sRESET) && (nextState === LTState.sRESET)) {
    resetMinWait := false.B
  }


  // Remote SBINIT pattern detection in LTState.sRESET only
  /*
    If [Management Transport protocol is not supported] OR [the SB_MGMT_UP flag is cleared to
    0], the SBINIT pattern (two consecutive iterations of 64-UI clock pattern and 32-UI low) is
    observed on any sideband Receiver clock/data pair

    TODO: Add the conditions for management transport protocol not supported and flag cleared
          before using this circuit
  */
  val sbInitPatternCounter = RegInit(0.U(2.W))
  val remoteTriggerTraining = Wire(Bool())
  val sbInitClkPattern = BigInt("5555555555555555", 16).U(64.W) // 0b0101_0101_..._0101

  remoteTriggerTraining := sbInitPatternCounter === 2.U

  // Training Retrigger Logic -- TODO: Fix this
  // val prevTrigger = RegInit(false.B)
  // val trainingRetryCounter = RegInit(0.U(retryAmtW.W))
  // val autoRetrain = Wire(Bool())
  // val retryCounterEn = Wire(Bool())
  // val retryAmtMax = Reg(UInt(retryAmtW.W))

  // IO connections
  io.trainingTimedout := trainingTimedout

  io.sidebandCtrlIo.txEn := true.B
  io.sidebandCtrlIo.rxEn := true.B
  io.sidebandCtrlIo.rxTxMode := SBRxTxMode.RAW
  io.sidebandCtrlIo.sbSerDesRst := false.B

  io.mainbandCtrlIo.txDataTriState.foreach(_ := false.B)
  io.mainbandCtrlIo.txClkTriState := false.B
  io.mainbandCtrlIo.txValidTriState := false.B
  io.mainbandCtrlIo.txTrackTriState := false.B
  io.mainbandCtrlIo.rxDataEn.foreach(_ := false.B)
  io.mainbandCtrlIo.rxClkEn := false.B
  io.mainbandCtrlIo.rxValidEn := false.B
  io.mainbandCtrlIo.rxTrackEn := false.B
  io.mainbandCtrlIo.freqSel := SpeedMode.speed4
  io.mainbandCtrlIo.rxTxMode := MBRxTxMode.RAW

    // For the ready/valid for the lanes
  io.sidebandLaneIo.rx.ready := false.B

  val pwrGood = io.pwrGood
  val sbPllLock = io.sidebandCtrlIo.sbPllLock
  val mbPllLock = io.mainbandCtrlIo.mbPllLock
  
  triggerTraining := io.swTriggerTraining || io.rdiTriggerTraining || remoteTriggerTraining

  // Substate FSMs
  // TODO: need to signal reset when LTSM transitions TrainError --> Reset
  val subFsmModuleReset = (reset.asBool || trainingTimedout).asAsyncReset

  // SBInit

  // MBInit

  // MBTrain

  // Training Basic Operations


  // State Machine
  currentState := nextState
  switch(currentState) {
    is(LTState.sRESET) {            
      /*  
      Default signals:
        Data, Valid, Clock TX are tri-state (tristate == 1)
        Data, Valid, Clock RX are disabled (en == 0)
        Sideband TX is enabled (en == 1)
        Sideband RX is enabled (en == 1)                
        Set Mainband Clock Speed to lowest (4 GT/s)
      */
      io.sidebandLaneIo.rx.ready := true.B
      when(io.sidebandLaneIo.rx.valid) {
        when(io.sidebandLaneIo.rx.bits.data(63,0) === sbInitClkPattern) {
          when(sbInitPatternCounter =/= 2.U) {
            sbInitPatternCounter := sbInitPatternCounter + 1.U
          }
        }.otherwise { 
          when(sbInitPatternCounter === 1.U) { // pattern not consecutively seen, so reset counter
            sbInitPatternCounter := 0.U
          }
        }
      }
 
      when(pwrGood && sbPllLock && mbPllLock && resetMinWait && triggerTraining) {
        nextState := LTState.sSBINIT
        sbInitPatternCounter := 0.U
      }.otherwise {
        nextState := LTState.sRESET
      }
    }

    is(LTState.sSBINIT) {        
      // SBInit doesn't take mainband ctrl io or lane io, so keep defaults for this state
      // in here


      // sbInitModule.io.fsmCtrl.start := true.B

      // when(sbInitModule.io.fsmCtrl.done === true.B) {
      //   // transition
      // }

      // logic for when there's a timeout
      // when(trainingTimedout || sbInitModule.io.fsmCtrl.error === true.B) {

      // }
    }

  }



}
