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
    bit saw_linkreset_before_disabled;

    bring_link_to_active();

    if (fdi.plStateSts !== RDI_STATE_ACTIVE || rdi.plStateSts !== RDI_STATE_ACTIVE) begin
      $fatal(1, "Step7 test did not start from Active");
    end

    // Keep RX inactive so active->{Disabled/LinkReset} transition is legal.
    fdi.lpRxActiveSts = 1'b0;
    wait_cycles(2);

    // Drive both state-entry responses from remote side, then allow stall handshake completion.
    // Spec intent: Disabled transition has higher priority than LinkReset.
    send_rdi_sideband_msg(sb_adapter0_rsp_linkreset());
    send_rdi_sideband_msg(sb_adapter0_rsp_disabled());

    @(negedge clock);
    rdi.plStallReq = 1'b1;

    saw_linkreset_before_disabled = 1'b0;
    for (cycle = 0; cycle < DEFAULT_WAIT_CYCLES; cycle++) begin
      @(posedge clock);
      if (fdi.plStateSts === RDI_STATE_LINKRESET) begin
        saw_linkreset_before_disabled = 1'b1;
      end
      if (fdi.plStateSts === RDI_STATE_DISABLED) begin
        break;
      end
    end

    if (fdi.plStateSts !== RDI_STATE_DISABLED) begin
      $fatal(1, "Did not reach Disabled after concurrent LinkReset/Disabled requests");
    end
    if (saw_linkreset_before_disabled) begin
      $fatal(1, "Observed LinkReset before Disabled; spec requires Disabled priority");
    end

    $display("D2D linkreset_disabled_priority completed");
    $finish;
  end
endmodule
