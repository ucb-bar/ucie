module ucie_d2d_common_checkers (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);

  import ucie_d2d_dv_pkg::*;

  default clocking cb @(posedge clock);
  endclocking

  fdi_active_entry_requires_rdi_active: assert property (
    disable iff (reset)
    (fdi.plStateSts == RDI_STATE_ACTIVE &&
     $past(fdi.plStateSts, 1, RDI_STATE_RESET) != RDI_STATE_ACTIVE)
      |-> rdi.plStateSts == RDI_STATE_ACTIVE
  ) else $error("FDI entered Active while RDI was not Active");

  fdi_linkerror_requires_rdi_linkerror: assert property (
    disable iff (reset)
    fdi.plStateSts == RDI_STATE_LINKERROR |-> rdi.plStateSts == RDI_STATE_LINKERROR
  ) else $error("FDI reached LinkError while RDI was not LinkError");

  rdi_stall_ack_rise_requires_stall_req: assert property (
    disable iff (reset)
    $rose(rdi.lpStallAck) |-> (rdi.plStallReq || $past(rdi.plStallReq, 1, 1'b0))
  ) else $error("RDI lpStallAck rose without plStallReq");

  rdi_stall_ack_clears_after_req_drop: assert property (
    disable iff (reset)
    $fell(rdi.plStallReq) |-> ##[1:4] !rdi.lpStallAck
  ) else $error("RDI lpStallAck did not clear after plStallReq dropped");
  
endmodule
