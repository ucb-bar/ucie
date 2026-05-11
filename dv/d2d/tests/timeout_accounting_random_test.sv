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
    int pre_pause_active_cycles;
    int inactive_pause_cycles;
    int pre_stall_active_cycles;
    int no_timeout_window_cycles;
    int post_stall_timeout_max_cycles;
    bit saw_linkerror;

    // Bounded random windows so the test finishes under default watchdog.
    pre_pause_active_cycles = $urandom_range(4, 12);
    inactive_pause_cycles = $urandom_range(8, 20);
    pre_stall_active_cycles = $urandom_range(4, 12);
    no_timeout_window_cycles = $urandom_range(16, 28);
    post_stall_timeout_max_cycles = $urandom_range(760, 860);

    @(negedge reset);
    rdi.drive_inband_present();
    wait_cycles(5);
    rdi.drive_state(RDI_STATE_ACTIVE);

    // Stage-3 parameter exchange starts when local ADV_CAP is emitted.
    recv_rdi_sideband_msg(msg, DEFAULT_WAIT_CYCLES);
    if (!sb_is_advcap_adapter(msg)) begin
      $fatal(1, "Expected adapter ADV_CAP sideband message, got 0x%032h", msg);
    end

    // 1) Consume some active budget.
    wait_cycles(pre_pause_active_cycles);

    // 2) Leave active; timeout must not fire while inactive.
    rdi.drive_state(RDI_STATE_RESET);
    for (cycle = 0; cycle < inactive_pause_cycles; cycle++) begin
      @(posedge clock);
      if (fdi.plStateSts === RDI_STATE_LINKERROR) begin
        $fatal(1, "Timeout advanced while RDI was inactive");
      end
    end

    // 3) Return active and consume additional budget.
    rdi.drive_state(RDI_STATE_ACTIVE);
    wait_cycles(pre_stall_active_cycles);

    // 4) Inject AdvCap.Stall near timeout budget and verify reset semantics.
    send_rdi_sideband_msg(sb_advcap_adapter_stall());
    for (cycle = 0; cycle < no_timeout_window_cycles; cycle++) begin
      @(posedge clock);
      if (fdi.plStateSts === RDI_STATE_LINKERROR) begin
        $fatal(1, "Timeout fired too early after AdvCap.Stall reset");
      end
    end

    // 5) Continue waiting with no remote AdvCap response; timeout must eventually fire.
    saw_linkerror = 1'b0;
    for (cycle = 0; cycle < post_stall_timeout_max_cycles; cycle++) begin
      @(posedge clock);
      if (fdi.plStateSts === RDI_STATE_LINKERROR) begin
        saw_linkerror = 1'b1;
        break;
      end
    end

    if (!saw_linkerror) begin
      $fatal(
        1,
        "Timeout did not fire within %0d cycles after random stall-reset window",
        post_stall_timeout_max_cycles
      );
    end

    $display(
      "D2D timeout_accounting_random completed: pre_pause=%0d inactive=%0d pre_stall=%0d no_to_win=%0d post_max=%0d",
      pre_pause_active_cycles, inactive_pause_cycles, pre_stall_active_cycles, no_timeout_window_cycles, post_stall_timeout_max_cycles
    );
    $finish;
  end

endmodule
