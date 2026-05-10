module ucie_d2d_test (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);
  import ucie_d2d_dv_pkg::*;
  `include "ucie_d2d_test_tasks.svh"

  always @(posedge clock) begin
    if (!reset && fdi.plStateSts == RDI_STATE_LINKERROR && rdi.plStateSts != RDI_STATE_LINKERROR) begin
      $fatal(1, "FDI LinkError appeared while RDI was not LinkError");
    end
  end

  initial begin
    bring_link_to_active();

    if (fdi.plStateSts !== RDI_STATE_ACTIVE || rdi.plStateSts !== RDI_STATE_ACTIVE) begin
      $fatal(1, "LinkError propagation test did not start from Active");
    end

    rdi.drive_state(RDI_STATE_LINKERROR);
    wait_fdi_state(RDI_STATE_LINKERROR, DEFAULT_WAIT_CYCLES);

    if (rdi.plStateSts !== RDI_STATE_LINKERROR) begin
      $fatal(1, "FDI LinkError was observed after RDI left LinkError");
    end

    if (fdi.plInbandPres) begin
      $fatal(1, "FDI inband presence stayed high in LinkError");
    end

    if (fdi.plRxActiveReq) begin
      $fatal(1, "FDI plRxActiveReq stayed high in LinkError");
    end

    $display("D2D linkerror_propagation completed");
    $finish;
  end
endmodule
