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
    int pause_cycles;
    int post_resume_max_cycles;
    bit saw_linkerror;

    if (!$value$plusargs("D2D_PARAM_TIMEOUT_PAUSE_CYCLES=%d", pause_cycles)) begin
      pause_cycles = 900;
    end
    if (!$value$plusargs("D2D_PARAM_TIMEOUT_POST_RESUME_MAX=%d", post_resume_max_cycles)) begin
      post_resume_max_cycles = 900;
    end

    @(negedge reset);
    rdi.drive_inband_present();
    wait_cycles(5);
    rdi.drive_state(RDI_STATE_ACTIVE);

    // Stage-3 parameter exchange starts when local ADV_CAP is emitted.
    recv_rdi_sideband_msg(msg, DEFAULT_WAIT_CYCLES);
    if (!sb_is_advcap_adapter(msg)) begin
      $fatal(1, "Expected adapter ADV_CAP sideband message, got 0x%032h", msg);
    end

    // Accumulate some active-time budget, then pause timeout counting by leaving RDI Active.
    wait_cycles(300);
    rdi.drive_state(RDI_STATE_RESET);
    for (cycle = 0; cycle < pause_cycles; cycle++) begin
      @(posedge clock);
      if (fdi.plStateSts === RDI_STATE_LINKERROR) begin
        $fatal(1, "Timeout advanced while RDI was not Active");
      end
    end

    // Resume Active; now timeout should continue and eventually fire.
    rdi.drive_state(RDI_STATE_ACTIVE);
    saw_linkerror = 1'b0;
    for (cycle = 0; cycle < post_resume_max_cycles; cycle++) begin
      @(posedge clock);
      if (fdi.plStateSts === RDI_STATE_LINKERROR) begin
        saw_linkerror = 1'b1;
        break;
      end
    end

    if (!saw_linkerror) begin
      $fatal(
        1,
        "Timeout did not fire after resuming RDI Active within %0d cycles",
        post_resume_max_cycles
      );
    end

    $display("D2D param_timeout_active_only completed");
    $finish;
  end
endmodule
