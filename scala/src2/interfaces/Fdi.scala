package edu.berkeley.cs.uciedigital.interfaces


import chisel3._
import chisel3.util._

case class FdiParams(width: Int = 64, dllpWidth: Int = 128, sbWidth: Int = 128)

/** The flit-aware die-to-die interface (FDI), from the perspective of the
  * protocol layer.
  */
class Fdi(params: FdiParams) extends Bundle {

  /** Protocol layer to Adapter data.
    *
    * Encompasses `lp_irdy`, `lp_valid`, and `pl_trdy` from the UCIe
    * specification.
    */
  val lpData = Decoupled3(Bits((8 * params.width).W))

  /** Adapter to protocol layer data.
    *
    * Encompasses `pl_valid` and `pl_data` from the UCIe specification. Note
    * that backpressure is not possible. Data should be sampled whenever valid
    * is asserted at a clock edge.
    */
  val plData = Flipped(Valid(Bits((8 * params.width).W)))

  /** When asserted at a rising clock edge, it indicates a single credit return
    * for the Retimer Receiver buffer. Each credit corresponds to 256B of
    * mainband data (including Flit header and CRC etc.). This signal must NOT
    * assert if a Retimer is not present.
    *
    * On FDI, this is an optional signal. It is permitted to have the Receiver
    * buffers in the Protocol Layer for Raw Format only. If this is not exposed
    * to Protocol Layer, Adapter must track credit at 256B granularity even for
    * Raw Format and return credits to Physical Layer on RDI.
    *
    * When this is exposed on FDI, the Adapter must have the initial credits
    * knowledge through other implementation specific means in order to
    * advertise this to the remote Link partner during parameter exchanges.
    */
  val lpRetimerCrd = Output(Bool())

  /** This signal is only applicable for CXL.cachemem in UCIe Flit Mode (i.e.,
    * the Adapter doing Retry) for CXL 256B Flit Mode. It is meant as a latency
    * optimization that enables detection and containment for viral or poison
    * using the Adapter to corrupt CRC of outgoing Flit. It is recommended to
    * corrupt CRC by performing a bitwise XOR of the computed CRC with the
    * syndrome 138Eh. The syndrome was computed such that no 1- bit or 2-bit
    * errors alias to this syndrome, and it has the least probability of
    * aliasing with 3-bit errors.
    *
    * For Standard 256B Flits, Protocol Layer asserts this along with lp_valid
    * for the last chunk of the Flit that needs containment. Adapter corrupts
    * CRC for both of the 128B halves of the Flit which had this set. It also
    * must make sure to overwrite this flit (with the next flit sent by the
    * Protocol Layer) in the Tx Retry buffer.
    *
    * For Latency-Optimized 256B Flits, Protocol Layer asserts this along with
    * lp_valid for the last chunk of the 128B Flit half that needs containment.
    * If lp_corrupt_crc is asserted on the first 128B half of the Flit, Protocol
    * Layer must assert it on the second 128B half of the Flit as well. The very
    * next Flit from the Protocol Layer after this signal has been asserted must
    * carry the information relevant for viral, as defined in the CXL
    * specification. If this was asserted on the second 128B half of the Flit
    * only, it is the responsibility of the Protocol Layer to send the first
    * 128B half exactly as before, and insert the viral information in the
    * second half of the Flit. Adapter corrupts CRC for the 128B half of the
    * Flit which had this set. It also must make sure to overwrite this flit
    * (with the next flit sent by the Protocol Layer) in the Tx Retry buffer.
    */
  val lpCorruptCrc = Output(Bool())

  /** Protocol Layer to Adapter transfer of DLLP bytes. This is not used for 68B
    * Flit Mode, CXL.cachemem or Streaming Protocols. For a 64B data path on
    * lp_data, it is recommended to assign NDLLP >= 8, so that 1 DLLP per Flit
    * can be transferred from the Protocol Layer to the Adapter on average. The
    * Adapter is responsible for inserting DLLP into DLP bytes 2:5 if the Flit
    * packing rules permit it. See Section 8.2.4.1 for additional rules.
    *
    * Encompasses `lp_dllp` and `lp_dllp_valid` from the UCIe specification.
    */
  val lpDllp = Valid(Bits(params.dllpWidth.W))

  /** Indicates that the corresponding DLLP bytes on lp_dllp follow the
    * Optimized_Update_FC format. It must stay asserted for the entire duration
    * of the DLLP transfer on lp_dllp.
    */
  val lpDllpOfc = Output(Bool())

  /** Protocol Layer to Adapter signal that indicates the stream ID to use with
    * data. Each stream ID maps to a unique protocol and stack.
    */
  val lpStream = Output(new ProtoStream())

  /** When asserted at a rising clock edge, it indicates a single credit return
    * from the Retimer. Each credit corresponds to 256B of mainband data
    * (including Flit header and CRC etc.). This signal must NOT assert if a
    * Retimer is not present. On FDI, this is an optional signal. It is
    * permitted to expose these credits to Protocol Layer for Raw Format only.
    * If this is not exposed to Protocol Layer, Adapter must track credit at
    * 256B granularity even for Raw Format and back-pressure the Protocol Layer
    * using pl_trdy. When this is exposed on FDI, the Adapter converts the
    * initial credits received from the Retimer over sideband to credit returns
    * to the Protocol Layer on this bit after Adapter LSM has moved to Active
    * state.
    */
  val plRetimerCrd = Input(Bool())

  /** Adapter to Protocol Layer transfer of DLLP bytes. This is not used for 68B
    * Flit Mode, CXL.cachemem or Streaming Protocols. For a 64B data path on
    * pl_data, it is recommended to assign NDLLP >= 8, so that 1 DLLP per Flit
    * can be transferred from the Adapter to the Protocol Layer, on average. The
    * Adapter is responsible for extracting DLLP from DLP bytes 2:5 if a Flit
    * Marker is not present. The Adapter is also responsible for indicating
    * Optimized_Update_FC format by setting pl_dllp_ofc = 1 for the
    * corresponding transfer on FDI.
    *
    * The valid signal indicates valid DLLP transfer on pl_dllp. DLLPs can be
    * transferred to the Protocol Layer whenever valid Flits can be transferred
    * on pl_data. There is no backpressure and the Protocol Layer must always
    * sink DLLPs.
    */
  val plDllp = Flipped(Valid(Bits(params.dllpWidth.W)))

  /** Indicates that the corresponding DLLP bytes on pl_dllp follow the
    * Optimized_Update_FC format. It must stay asserted for the entire duration
    * of the DLLP transfer on pl_dllp.
    */
  val plDllpOfc = Input(Bool())

  /** Adapter to Protocol Layer signal that indicates the stream ID to use with
    * data. Each stream ID maps to a unique protocol.
    */

  val plStream = Input(new ProtoStream())

  /** Adapter to Protocol Layer indication to dump a Flit. This enables latency
    * optimizations on the Receiver data path when CRC checking is enabled in
    * the Adapter. It is not applicable for Raw Format or 68B Flit Format. For
    * Standard 256B Flit, it is required to have a fixed number of clock cycle
    * delay between the last chunk of a Flit transfer and the assertion of
    * pl_flit_cancel. This delay is fixed to be 1 cycle (i.e., the cycle after
    * the last chunk transfer of a Flit). When this signal is asserted, Protocol
    * Layer must not consume the associated Flit. For Latency-Optimized 256B
    * Flits, it is required to have a fixed number of clock cycle delay between
    * the last chunk of a 128B half Flit transfer and the assertion of
    * pl_flit_cancel. This delay is fixed to be 1 cycle (i.e., the cycle after
    * the last transfer of the corresponding 128B chunk). When this signal is
    * asserted, Protocol Layer must not consume the associated Flit half. When
    * this mode is supported, Protocol Layer must support it for all applicable
    * Flit Formats associated with the corresponding protocol. Adapter must
    * guarantee this to be a single cycle pulse when dumping a Flit or Flit
    * half. It is the responsibility of the Adapter to ensure that the canceled
    * Flits or Flit halves are eventually replayed on the interface without
    * cancellation in the correct order once they pass CRC after Retry etc. See
    * Section 8.2.5 for examples.
    */
  val plFlitCancel = Input(Bool())

  /** Protocol Layer request to Adapter to request state change. */
  val lpStateReq = Output(PhyStateReq())

  /** Protocol Layer to Adapter indication that an error has occurred which
    * requires the Link to go down. Adapter must propagate this request to RDI,
    * and move the Adapter LSMs (and CXL vLSMs if applicable) to LinkError state
    * once RDI is in LinkError state. It must stay there as long as
    * lp_linkerror=1. The reason for having this be an indication decoupled from
    * regular state transitions is to allow immediate action on part of the
    * Protocol Layer and Adapter in order to provide the quickest path for error
    * containment when applicable (for example, a viral error escalation could
    * map to the LinkError state)
    */
  val lpLinkError = Output(Bool())

  /** Adapter to Protocol Layer Status indication of the Interface.
    *
    * The status signal is permitted to transition from Adapter autonomously
    * when applicable. For example the Adapter asserts the Retrain status when
    * it decides to enter retraining either autonomously or when requested by
    * remote agent. For PCIe/Streaming protocols, the Adapter LSM is exposed as
    * pl_state_sts to the Protocol Layer. For CXL protocol, the ARB/MUX vLSM is
    * exposed as pl_state_status to the Protocol Layer. The Link Status is
    * considered to be Up from Protocol Layer perspective when FDI status is
    * Active, Active.PMNAK, Retrain, L1 or L2. The Link Status is considered
    * Down for other states of FDI.
    */
  val plStateStatus = Input(PhyState())

  /** Adapter to the Protocol Layer indication that the Die-to-Die Link has
    * finished negotiation of parameters with remote Link partner and is ready
    * for transitioning the FDI Link State Machine (LSM) to Active. Once it
    * transitions to 1b, this must stay 1b until FDI moves to Active or
    * LinkError. It stays asserted while FDI is in Retrain, Active.PMNAK, L1 or
    * L2. It must de-assert during LinkReset, Disabled or LinkError states.
    */
  val plInbandPres = Input(Bool())

  /** Adapter to the Protocol Layer indication that it has detected a framing
    * related error. It is pipeline matched with the receive data path. It must
    * also assert if pl_error was asserted on RDI by the Physical Layer for a
    * Flit which the Adapter is forwarding to the Protocol Layer. It is
    * permitted for Protocol Layer to use pl_error indication to log correctable
    * errors when Retry is enabled from the Adapter. The Adapter must finish any
    * partial Flits sent to the Protocol Layer and assert pl_flit_cancel in
    * order to prevent consumption of that Flit by the Protocol Layer. Adapter
    * must initiate Link Retrain on RDI following this, if it was a framing
    * error detected by the Adapter.
    */
  val plError = Input(Bool())

  /** Adapter to the Protocol Layer indication that a correctable error was
    * detected that does not affect the data path and will not cause Retrain on
    * the Link. The Protocol Layer must OR the pl_error and pl_cerror signals
    * for Correctable Error Logging. The Adapter must OR any internally detected
    * errors with the pl_cerror indication on RDI and forward it to the Protocol
    * Layer
    */
  val plCerror = Input(Bool())

  /** Adapter to the Protocol Layer indication that a non-fatal error was
    * detected. This is used by Protocol Layer for error logging and
    * corresponding escalation to software. The Adapter must OR any internally
    * detected errors with pl_nferror on RDI and forward the result on FDI.
    */
  val plNfError = Input(Bool())

  /** Indicates a fatal error from the Adapter. Adapter must transition
    * pl_state_sts to LinkError if not already in LinkError state.
    * Implementations are permitted to map any fatal error to this signal that
    * require upper layer escalation (or interrupt generation) depending on
    * system level requirements.
    */
  val plTrainError = Input(Bool())

  /** Adapter asserts this signal to request the Protocol Layer to open its
    * Receiver’s data path and get ready for receiving protocol data or Flits.
    * The rising edge of this signal must be when pl_state_sts is Reset, Retrain
    * or Active. Together with lp_rx_active_sts, it forms a four way handshake.
    * See Section 8.2.7 for rules related to this handshake.
    */
  val plRxActiveReq = Input(Bool())

  /** Protocol Layer responds to pl_rx_active_req after it is ready to receive
    * and parse protocol data or Flits. Together with pl_rx_active_req, it forms
    * a four way handshake. See Section 8.2.7 for rules related to this
    * handshake.
    */
  val lpRxActiveStatus = Output(Bool())

  /** Adapter indication to Protocol Layer the protocol that was negotiated
    * during training.
    */
  val plProtocol = Input(Protocol())

  /** This indicates the negotiated Format. See Chapter 3.0 for the definitions
    * of these formats.
    */
  val plProtocolFlitFormat = Input(FlitFormat())

  /** Indication that pl_protocol, and pl_protocol_flitfmt have valid
    * information. This is a level signal, asserted when the Adapter has
    * determined the appropriate protocol, but must only de-assert again after
    * subsequent transitions to LinkError or Reset state depending on the Link
    * state machine transitions. Protocol Layer must sample and store
    * pl_protocol and pl_protocol_flitfmt when pl_protocol_vld = 1 and
    * pl_state_sts = Reset and pl_inband_pres = 1. It must treat this saved
    * value as the negotiated protocol until pl_state_sts = Reset and
    * pl_inband_pres = 0. The Adapter must ensure that if pl_inband_pres = 1,
    * pl_protocol_vld = 1 and pl_state_sts = Reset, then pl_protocol and
    * pl_protocol_flitfmt are the correct values that can be sampled by the
    * Protocol Layer.
    */
  val plProtocolValid = Input(Bool())

  /** Adapter request to Protocol Layer to flush all Flits for state transition
    * and not prepare any new Flits. See Section 8.2.6 for details.
    */
  val plStallReq = Input(Bool())

  /** Protocol Layer to Adapter indication that the Flits are aligned and
    * stalled (if pl_stallreq was asserted). It is strongly recommended that
    * this response logic be on a global free running clock, so the Protocol
    * Layer can respond to pl_stallreq with lp_stallack even if other
    * significant portions of the Protocol Layer are clock gated.
    */
  val lpStallAck = Output(Bool())

  /** Adapter indication to Protocol Layer that the Link is doing training or
    * retraining (i.e., RDI has pl_phyinrecenter asserted or the Adapter LSM has
    * not moved to Active yet). If this is asserted during a state where clock
    * gating is permitted, the pl_clk_req/lp_clk_ack handshake must be performed
    * with the upper layer. The upper layers are permitted to use this to update
    * the “Link Training/Retraining” bit in the UCIe Link Status register.
    */
  val plPhyInRecenter = Input(Bool())

  /** Adapter indication to Protocol Layer that the Physical Layer is in L1
    * power management state (i.e., RDI is in L1 state).
    */
  val plPhyInL1 = Input(Bool())

  /** Adapter indication to Protocol Layer that the Physical Layer is in L2
    * power management state (i.e., RDI is in L2 state).
    */
  val plPhyInL2 = Input(Bool())

  /** Current Link speed. The Protocol Layer must only consider this signal to
    * be relevant when the FDI state is Active or Retrain. For multi-module
    * configurations, all modules must operate at the same speed.
    */
  val plSpeedMode = Input(SpeedMode())

  /** Current Link Configuration. Indicates the current operating width of a
    * module. The Protocol Layer must only consider this signal to be relevant
    * when the FDI state is Active or Retrain. This is the total width across
    * all Active modules for the corresponding FDI instance.
    */
  val plLinkWidth = Input(PhyWidth())

  /** Request from the Adapter to remove clock gating from the internal logic of
    * the Protocol Layer. This is an asynchronous signal from the Protocol
    * Layer’s perspective since it is not tied to lclk being available in the
    * Protocol Layer. Together with lp_clk_ack, it forms a four-way handshake to
    * enable dynamic clock gating in the Protocol Layer. When dynamic clock
    * gating is supported, the Protocol Layer must use this signal to exit clock
    * gating before responding with lp_clk_ack. If dynamic clock gating is not
    * supported, it is permitted for the Adapter to tie this signal to 1b.
    */
  val plClkReq = Input(Bool())

  /** Response from the Protocol Layer to the Adapter acknowledging that its
    * clocks have been ungated in response to pl_clk_req. This signal is only
    * asserted when pl_clk_req is asserted, and de-asserted after pl_clk_req has
    * de-asserted. When dynamic clock gating is not supported by the Protocol
    * Layer, it must stage pl_clk_req internally for one or more clock cycles
    * and turn it around as lp_clk_ack. This way it will still participate in
    * the handshake even though it does not support dynamic clock gating.
    */
  val lpClkAck = Output(Bool())

  /** Request from the Protocol Layer to remove clock gating from the internal
    * logic of the Adapter. This is an asynchronous signal relative to lclk from
    * the Adapter’s perspective since it is not tied to lclk being available in
    * the Adapter. Together with pl_wake_ack, it forms a four-way handshake to
    * enable dynamic clock gating in the Adapter. When dynamic clock gating is
    * supported, the Adapter must use this signal to exit clock gating before
    * responding with pl_wake_ack. If dynamic clock gating is not supported, it
    * is permitted for the Protocol Layer to tie this signal to 1b.
    */
  val lpWakeReq = Output(Bool())

  /** Response from the Adapter to the Protocol Layer acknowledging that its
    * clocks have been ungated in response to lp_wake_req. This signal is only
    * asserted after lp_wake_req has asserted, and is de-asserted after
    * lp_wake_req has de-asserted. When dynamic clock gating is not supported by
    * the Adapter, it must stage lp_wake_req internally for one or more clock
    * cycles and turn it around as pl_wake_ack. This way it will still
    * participate in the handshake even though it does not support dynamic clock
    * gating.
    */
  val plWakeAck = Input(Bool())

  /** This is the sideband interface from the Adapter to the Protocol Layer. See
    * Chapter 6.0 for details. NC is the width of the interface. Supported
    * values are 8, 16, and 32.
    *
    * When valid is asserted, it indicates that pl_cfg has valid information
    * that should be consumed by the Protocol Layer.
    */
  val plConfig = Flipped(Valid(Bits(params.sbWidth.W)))

  /** Credit return for sideband packets from the Adapter to the Protocol Layer
    * for sideband packets. Each credit corresponds to 64 bits of header and 64
    * bits of data. Even transactions that don’t carry data or carry 32 bits of
    * data consume the same credit and the Receiver returns the credit once the
    * corresponding transaction has been processed or de-allocated from its
    * internal buffers. See Section 6.1.3.1 for additional flow control rules.
    * Because the advertised credits are design parameters, the Protocol Layer
    * transmitter updates the credit counters with initial credits on domain
    * reset exit, and no initialization credits are returned over the interface.
    * Credit returns must follow the same rules of clock gating exit handshakes
    * as the sideband packets to ensure that no credit returns are dropped by
    * the receiver of the credit returns.
    */
  val plConfigCredit = Output(Bool())

  /** This is the sideband interface from Protocol Layer to the Adapter. See
    * Chapter 6.0 for details. NC is the width of the interface. Supported
    * values are 8, 16, and 32.
    */
  val lpConfig = Valid(Bits(params.sbWidth.W))

  /** Credit return for sideband packets from the Protocol Layer to the Adapter
    * for sideband packets. Each credit corresponds to 64 bits of header and 64
    * bits of data. Even transactions that don’t carry data or carry 32 bits of
    * data consume the same credit and the Receiver returns the credit once the
    * corresponding transaction has been processed or de-allocated from its
    * internal buffers. See Section 6.1.3.1 for additional flow control rules.
    * Because the advertised credits are design parameters, the Adapter
    * transmitter updates the credit counters with initial credits on domain
    * reset exit, and no initialization credits are returned over the interface.
    * Credit returns must follow the same rules of clock gating exit handshakes
    * as the sideband packets to ensure that no credit returns are dropped by
    * the receiver of the credit returns.
    */
  val lpConfigCredit = Input(Bool())
}