/*
  The link training states. LTState is the coarse state the LTSM runs on, and
  LTSMState is the sec 9.5.3.34 encoding the error log reports. Both are in
  common because the RDI rules name LTSM states.
 */

package edu.berkeley.cs.uciedigital.common

import chisel3._

object LTState extends ChiselEnum {
  val sRESET, sSBINIT, sMBINIT, sMBTRAIN, sLINKINIT, sACTIVE, sPHYRETRAIN,
      sTRAINERROR, sL1_L2 = Value
}

object LTSMState extends ChiselEnum {
  val sRESET = Value("h00".U)
  val sSBINIT = Value("h01".U)
  val sMBINIT_PARAM = Value("h02".U)
  val sMBINIT_CAL = Value("h03".U)
  val sMBINIT_REPAIRCLK = Value("h04".U)
  val sMBINIT_REPAIRVAL = Value("h05".U)
  val sMBINIT_REVERSALMB = Value("h06".U)
  val sMBINIT_REPAIRMB = Value("h07".U)
  val sMBTRAIN_VALVREF = Value("h08".U)
  val sMBTRAIN_DATAVREF = Value("h09".U)
  val sMBTRAIN_SPEEDIDLE = Value("h0A".U)
  val sMBTRAIN_TXSELFCAL = Value("h0B".U)
  val sMBTRAIN_RXCLKCAL = Value("h0C".U)
  val sMBTRAIN_VALTRAINCENTER = Value("h0D".U)
  val sMBTRAIN_VALTRAINVREF = Value("h0E".U)
  val sMBTRAIN_DATATRAINCENTER1 = Value("h0F".U)
  val sMBTRAIN_DATATRAINVREF = Value("h10".U)
  val sMBTRAIN_RXDESKEW = Value("h11".U)
  val sMBTRAIN_DATATRAINCENTER2 = Value("h12".U)
  val sMBTRAIN_LINKSPEED = Value("h13".U)
  val sMBTRAIN_REPAIR = Value("h14".U)
  val sPHYRETRAIN = Value("h15".U)
  val sLINKINIT = Value("h16".U)
  val sACTIVE = Value("h17".U)
  val sTRAINERROR = Value("h18".U)
  val sL1_L2 = Value("h19".U)

  // UcieRegBridge writes this enum straight into the Error Log, so each value
  // must be its sec 9.5.3.34 code.
  val specEncoding: Seq[(String, Int, Type)] = Seq(
    ("RESET", 0x00, sRESET),
    ("SBINIT", 0x01, sSBINIT),
    ("MBINIT.PARAM", 0x02, sMBINIT_PARAM),
    ("MBINIT.CAL", 0x03, sMBINIT_CAL),
    ("MBINIT.REPAIRCLK", 0x04, sMBINIT_REPAIRCLK),
    ("MBINIT.REPAIRVAL", 0x05, sMBINIT_REPAIRVAL),
    ("MBINIT.REVERSALMB", 0x06, sMBINIT_REVERSALMB),
    ("MBINIT.REPAIRMB", 0x07, sMBINIT_REPAIRMB),
    ("MBTRAIN.VALVREF", 0x08, sMBTRAIN_VALVREF),
    ("MBTRAIN.DATAVREF", 0x09, sMBTRAIN_DATAVREF),
    ("MBTRAIN.SPEEDIDLE", 0x0a, sMBTRAIN_SPEEDIDLE),
    ("MBTRAIN.TXSELFCAL", 0x0b, sMBTRAIN_TXSELFCAL),

    // sec 9.5.3.34 names 0Ch RXSELFCAL in error. The state is RXCLKCAL.
    ("MBTRAIN.RXCLKCAL", 0x0c, sMBTRAIN_RXCLKCAL),
    ("MBTRAIN.VALTRAINCENTER", 0x0d, sMBTRAIN_VALTRAINCENTER),
    ("MBTRAIN.VALTRAINVREF", 0x0e, sMBTRAIN_VALTRAINVREF),
    ("MBTRAIN.DATATRAINCENTER1", 0x0f, sMBTRAIN_DATATRAINCENTER1),
    ("MBTRAIN.DATATRAINVREF", 0x10, sMBTRAIN_DATATRAINVREF),
    ("MBTRAIN.RXDESKEW", 0x11, sMBTRAIN_RXDESKEW),
    ("MBTRAIN.DATATRAINCENTER2", 0x12, sMBTRAIN_DATATRAINCENTER2),
    ("MBTRAIN.LINKSPEED", 0x13, sMBTRAIN_LINKSPEED),
    ("MBTRAIN.REPAIR", 0x14, sMBTRAIN_REPAIR),
    ("PHYRETRAIN", 0x15, sPHYRETRAIN),
    ("LINKINIT", 0x16, sLINKINIT),
    ("ACTIVE", 0x17, sACTIVE),
    ("TRAINERROR", 0x18, sTRAINERROR),
    ("L1/L2", 0x19, sL1_L2)
  )

  specEncoding.foreach { case (name, code, value) =>
    require(
      value.litValue == code,
      f"sec 9.5.3.34 gives $name the encoding 0x$code%02X, " +
        f"LTSMState has 0x${value.litValue}%02X"
    )
  }

  // A state added without a table row passes every row check, so check the
  // count too.
  require(
    all.length == specEncoding.length,
    s"LTSMState has ${all.length} states and sec 9.5.3.34 defines " +
      s"${specEncoding.length}; every state must have a spec encoding, and " +
      "\"all other encodings are reserved\""
  )
}
