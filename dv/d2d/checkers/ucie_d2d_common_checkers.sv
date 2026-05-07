module ucie_d2d_common_checkers (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);

  import ucie_d2d_dv_pkg::*;

  default clocking cb @(posedge clock);
  endclocking

  fdi_active_requires_rdi_active: assert property (
    disable iff (reset)
    fdi.plStateSts == RDI_STATE_ACTIVE |-> rdi.plStateSts == RDI_STATE_ACTIVE
  ) else $error("FDI reached Active while RDI was not Active");

  fdi_linkerror_requires_rdi_linkerror: assert property (
    disable iff (reset)
    fdi.plStateSts == RDI_STATE_LINKERROR |-> rdi.plStateSts == RDI_STATE_LINKERROR
  ) else $error("FDI reached LinkError while RDI was not LinkError");

  rdi_stall_ack_requires_stall_req: assert property (
    disable iff (reset)
    rdi.lpStallAck |-> rdi.plStallReq
  ) else $error("RDI lpStallAck asserted without plStallReq");
  
endmodule
