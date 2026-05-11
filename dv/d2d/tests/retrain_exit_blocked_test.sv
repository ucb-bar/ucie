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
    bit saw_stall_req;

    bring_link_to_active();

    if (fdi.plStateSts !== RDI_STATE_ACTIVE || rdi.plStateSts !== RDI_STATE_ACTIVE) begin
      $fatal(1, "Retrain-exit-blocked test did not start from Active");
    end

    // Enter retrain path.
    rdi.drive_state(RDI_STATE_RETRAIN);

    for (cycle = 0; cycle < DEFAULT_WAIT_CYCLES && fdi.plRxActiveReq !== 1'b0; cycle++) begin
      @(posedge clock);
    end
    if (fdi.plRxActiveReq !== 1'b0) begin
      $fatal(1, "FDI plRxActiveReq did not deassert for retrain transition");
    end
    fdi.lpRxActiveSts = 1'b0;

    for (cycle = 0; cycle < DEFAULT_WAIT_CYCLES && !saw_stall_req; cycle++) begin
      @(posedge clock);
      if (fdi.plStallReq === 1'b1) begin
        saw_stall_req = 1'b1;
      end
    end
    if (!saw_stall_req) begin
      $fatal(1, "FDI plStallReq was not asserted for retrain transition");
    end

    // Complete entry handshake into retrain.
    fdi.lpStallAck = 1'b1;
    for (cycle = 0; cycle < DEFAULT_WAIT_CYCLES && fdi.plStateSts !== RDI_STATE_RETRAIN; cycle++) begin
      @(posedge clock);
    end
    if (fdi.plStateSts !== RDI_STATE_RETRAIN) begin
      $fatal(1, "FDI did not enter Retrain");
    end

    // Attempt a premature return-to-Active at the PHY side.
    // Adapter should hold retrain and not immediately expose Active again.
    rdi.drive_state(RDI_STATE_ACTIVE);
    fdi.request_active();
    wait_cycles(40);

    if (fdi.plStateSts === RDI_STATE_ACTIVE) begin
      $fatal(1, "FDI returned to Active prematurely during retrain-exit attempt");
    end

    if (rdi.lpStateReq === RDI_STATE_REQ_ACTIVE) begin
      $fatal(1, "Adapter requested Active on RDI during blocked retrain-exit window");
    end

    // Clean up driven controls.
    fdi.clear_state_request();
    fdi.lpStallAck = 1'b0;

    $display("D2D retrain_exit_blocked completed");
    $finish;
  end
endmodule
