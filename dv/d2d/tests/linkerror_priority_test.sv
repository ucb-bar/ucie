module ucie_d2d_test (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);
  import ucie_d2d_dv_pkg::*;
  `include "ucie_d2d_test_tasks.svh"

  initial begin
    bring_link_to_active();

    fdi.lpStateReq = RDI_STATE_REQ_LINKRESET;
    rdi.drive_state(RDI_STATE_LINKERROR);

    wait_fdi_state(RDI_STATE_LINKERROR, DEFAULT_WAIT_CYCLES);

    if (fdi.plStateSts == RDI_STATE_LINKRESET) begin
      $fatal(1, "FDI entered LinkReset instead of prioritizing LinkError");
    end

    $display("D2D linkerror_priority completed");
    $finish;
  end
endmodule
