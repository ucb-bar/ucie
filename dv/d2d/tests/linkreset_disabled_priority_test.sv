module ucie_d2d_test (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);
  import ucie_d2d_dv_pkg::*;
  `include "ucie_d2d_test_tasks.svh"

  initial begin
    logic [127:0] msg;
    logic seen_rsp_disabled;
    logic seen_rsp_linkreset;
    int cycle;

    bring_link_to_active();

    if (fdi.plStateSts !== RDI_STATE_ACTIVE || rdi.plStateSts !== RDI_STATE_ACTIVE) begin
      $fatal(1, "Step7 test did not start from Active");
    end

    // Keep RX inactive so active->{Disabled/LinkReset} transition is legal.
    fdi.lpRxActiveSts = 1'b0;
    wait_cycles(2);

    // Send both remote requests while stall handshake is still blocked.
    // Expected: DUT emits both responses and finally enters Disabled (priority over LinkReset).
    send_rdi_sideband_msg(sb_adapter0_req_linkreset());
    send_rdi_sideband_msg(sb_adapter0_req_disabled());

    seen_rsp_disabled = 1'b0;
    seen_rsp_linkreset = 1'b0;
    for (cycle = 0; cycle < DEFAULT_WAIT_CYCLES; cycle++) begin
      recv_rdi_sideband_msg(msg, DEFAULT_WAIT_CYCLES);
      if (sb_is_adapter0_rsp_disabled(msg)) begin
        seen_rsp_disabled = 1'b1;
      end
      if (sb_is_adapter0_rsp_linkreset(msg)) begin
        seen_rsp_linkreset = 1'b1;
      end
      if (seen_rsp_disabled && seen_rsp_linkreset) begin
        break;
      end
    end

    if (!seen_rsp_disabled) begin
      $fatal(1, "Did not observe adapter RSP_DISABLED after REQ_DISABLED");
    end
    if (!seen_rsp_linkreset) begin
      $fatal(1, "Did not observe adapter RSP_LINKRESET after REQ_LINKRESET");
    end

    // Complete stall handshake after both requests are latched.
    @(negedge clock);
    rdi.plStallReq = 1'b1;

    wait_fdi_state(RDI_STATE_DISABLED, DEFAULT_WAIT_CYCLES);
    if (fdi.plStateSts === RDI_STATE_LINKRESET) begin
      $fatal(1, "Entered LinkReset when Disabled should have priority");
    end

    $display("D2D linkreset_disabled_priority completed");
    $finish;
  end
endmodule
