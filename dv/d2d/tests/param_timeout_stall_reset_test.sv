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
    int cycle;
    int pre_stall_cycles;
    int no_timeout_window_cycles;
    int post_stall_timeout_cycles;
    bit saw_linkerror;

    if (!$value$plusargs("D2D_PARAM_TIMEOUT_PRE_STALL_CYCLES=%d", pre_stall_cycles)) begin
      pre_stall_cycles = 500;
    end
    if (!$value$plusargs("D2D_PARAM_TIMEOUT_NO_TIMEOUT_WINDOW=%d", no_timeout_window_cycles)) begin
      no_timeout_window_cycles = 300;
    end
    if (!$value$plusargs("D2D_PARAM_TIMEOUT_POST_STALL_MAX=%d", post_stall_timeout_cycles)) begin
      post_stall_timeout_cycles = 900;
    end

    @(negedge reset);
    rdi.drive_inband_present();
    wait_cycles(5);
    rdi.drive_state(RDI_STATE_ACTIVE);

    recv_rdi_sideband_msg(msg, DEFAULT_WAIT_CYCLES);
    if (!sb_is_advcap_adapter(msg)) begin
      $fatal(1, "Expected adapter ADV_CAP sideband message, got 0x%032h", msg);
    end

    // Build elapsed timeout budget, then inject AdvCap.Stall to reset the timer.
    wait_cycles(pre_stall_cycles);
    send_rdi_sideband_msg(sb_advcap_adapter_stall());

    // No timeout should occur in this post-stall window if timer reset worked.
    for (cycle = 0; cycle < no_timeout_window_cycles; cycle++) begin
      @(posedge clock);
      if (fdi.plStateSts === RDI_STATE_LINKERROR) begin
        $fatal(1, "Timeout fired too early after AdvCap.Stall reset");
      end
    end

    // Continue waiting; timeout should eventually occur without a real AdvCap response.
    saw_linkerror = 1'b0;
    for (cycle = 0; cycle < post_stall_timeout_cycles; cycle++) begin
      @(posedge clock);
      if (fdi.plStateSts === RDI_STATE_LINKERROR) begin
        saw_linkerror = 1'b1;
        break;
      end
    end

    if (!saw_linkerror) begin
      $fatal(
        1,
        "Timeout did not occur within %0d cycles after AdvCap.Stall reset window",
        post_stall_timeout_cycles
      );
    end

    $display("D2D param_timeout_stall_reset completed");
    $finish;
  end
endmodule
