module ucie_d2d_test (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);
  import ucie_d2d_dv_pkg::*;
  `include "ucie_d2d_test_tasks.svh"

  initial begin
    int cycle;

    bring_link_to_active();

    if (fdi.plStateSts !== RDI_STATE_ACTIVE || rdi.plStateSts !== RDI_STATE_ACTIVE) begin
      $fatal(1, "Retrain propagation test did not start from Active");
    end

    rdi.drive_state(RDI_STATE_RETRAIN);

    for (cycle = 0; cycle < DEFAULT_WAIT_CYCLES && fdi.plStateSts !== RDI_STATE_RETRAIN; cycle++) begin
      @(posedge clock);
      if (fdi.plStateSts === RDI_STATE_LINKERROR) begin
        $fatal(1, "FDI entered LinkError while checking retrain propagation");
      end
      if (rdi.plStateSts !== RDI_STATE_RETRAIN) begin
        $fatal(1, "RDI left Retrain before FDI observed Retrain");
      end
    end

    if (fdi.plStateSts !== RDI_STATE_RETRAIN) begin
      $fatal(1, "FDI did not enter Retrain after RDI entered Retrain");
    end

    if (fdi.plRxActiveReq) begin
      $fatal(1, "FDI plRxActiveReq remained asserted in Retrain");
    end

    if (fdi.plInbandPres !== 1'b1) begin
      $fatal(1, "FDI inband presence deasserted unexpectedly in Retrain");
    end

    $display("D2D rdi_retrain_propagation completed");
    $finish;
  end
endmodule
