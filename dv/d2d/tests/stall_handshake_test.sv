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
    bit drained_tx_beat;
    logic [DATA_BITS-1:0] pending_data;

    bring_link_to_active();

    if (fdi.plStateSts !== RDI_STATE_ACTIVE || rdi.plStateSts !== RDI_STATE_ACTIVE) begin
      $fatal(1, "Stall/retrain test did not start from Active");
    end

    // Hold RDI not-ready so one FDI TX beat remains pending in adapter datapath.
    pending_data = 'h0123_4567_89ab_cdef_fedc_ba98_7654_3210_0011_2233_4455_6677_8899_aabb_ccdd_eeff;
    @(negedge clock);
    rdi.plTrdy = 1'b0;
    fdi.lpIrdy = 1'b1;
    fdi.lpValid = 1'b1;
    fdi.lpData = pending_data;

    for (cycle = 0; cycle < DEFAULT_WAIT_CYCLES && fdi.plTrdy !== 1'b1; cycle++) begin
      @(posedge clock);
    end
    if (fdi.plTrdy !== 1'b1) begin
      $fatal(1, "FDI beat was never accepted into adapter buffer");
    end
    @(posedge clock);
    fdi.lpIrdy = 1'b0;
    fdi.lpValid = 1'b0;
    fdi.lpData = '0;

    // Trigger retrain from RDI while a TX beat is pending.
    rdi.drive_state(RDI_STATE_RETRAIN);

    for (cycle = 0; cycle < DEFAULT_WAIT_CYCLES && fdi.plRxActiveReq !== 1'b0; cycle++) begin
      @(posedge clock);
    end
    if (fdi.plRxActiveReq !== 1'b0) begin
      $fatal(1, "FDI plRxActiveReq did not deassert during retrain transition");
    end
    fdi.lpRxActiveSts = 1'b0;

    for (cycle = 0; cycle < DEFAULT_WAIT_CYCLES && !saw_stall_req; cycle++) begin
      @(posedge clock);
      if (fdi.plStallReq === 1'b1) begin
        saw_stall_req = 1'b1;
      end
    end
    if (!saw_stall_req) begin
      $fatal(1, "FDI plStallReq was not asserted during retrain handshake");
    end

    // Allow pending TX beat to drain before acknowledging stall.
    @(negedge clock);
    rdi.plTrdy = 1'b1;
    for (cycle = 0; cycle < DEFAULT_WAIT_CYCLES && !drained_tx_beat; cycle++) begin
      @(posedge clock);
      if (rdi.lpValid && rdi.lpIrdy && rdi.plTrdy) begin
        if (rdi.lpData !== pending_data) begin
          $fatal(1, "Drained TX beat mismatch during retrain handshake");
        end
        drained_tx_beat = 1'b1;
      end
    end
    if (!drained_tx_beat) begin
      $fatal(1, "Pending TX beat did not drain before stall acknowledge");
    end

    // Complete the protocol-side stall handshake.
    @(negedge clock);
    fdi.lpStallAck = 1'b1;

    for (cycle = 0; cycle < DEFAULT_WAIT_CYCLES && fdi.plStateSts !== RDI_STATE_RETRAIN; cycle++) begin
      @(posedge clock);
      if (fdi.plStateSts === RDI_STATE_LINKERROR) begin
        $fatal(1, "FDI entered LinkError while waiting for Retrain");
      end
    end
    if (fdi.plStateSts !== RDI_STATE_RETRAIN) begin
      $fatal(1, "FDI did not enter Retrain after stall handshake completion");
    end

    if (rdi.lpStateReq !== RDI_STATE_REQ_RETRAIN && rdi.lpStateReq !== RDI_STATE_REQ_NOP) begin
      $fatal(1, "Unexpected RDI lpStateReq during retrain transition: 0x%0h", rdi.lpStateReq);
    end

    // In the current adapter-only wrapper, no dedicated retrain sideband message
    // is expected on this path.
    if (rdi.lpCfgVld === 1'b1) begin
      $fatal(1, "Unexpected sideband traffic observed during retrain handshake");
    end

    @(negedge clock);
    fdi.lpStallAck = 1'b0;

    $display("D2D stall_handshake completed");
    $finish;
  end
endmodule
