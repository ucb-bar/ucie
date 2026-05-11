module ucie_d2d_test (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);
  import ucie_d2d_dv_pkg::*;
  `include "ucie_d2d_test_tasks.svh"

  int rounds;
  int max_gap_cycles;
  int max_rsp_delay_cycles;
  int no_timeout_window_cycles;
  int i;

  task automatic wait_rand_gap(input int max_gap);
    int gap;
    begin
      if (max_gap <= 0) begin
        return;
      end
      gap = $urandom_range(0, max_gap);
      wait_cycles(gap);
    end
  endtask

  task automatic expect_rsp_active_from_adapter(input int max_cycles);
    logic [127:0] msg;
    begin
      recv_rdi_sideband_msg(msg, max_cycles);
      if (!sb_is_adapter0_rsp_active(msg)) begin
        $fatal(1, "Expected adapter RSP_ACTIVE, got 0x%032h", msg);
      end
    end
  endtask

  task automatic try_recv_rdi_sideband_msg(
    output bit got_msg,
    output logic [127:0] msg,
    input int max_cycles
  );
    int beat;
    int cycle;
    begin
      got_msg = 1'b0;
      msg = '0;
      for (cycle = 0; cycle < max_cycles && rdi.lpCfgVld !== 1'b1; cycle++) begin
        @(posedge clock);
      end
      if (rdi.lpCfgVld === 1'b1) begin
        got_msg = 1'b1;
        for (beat = 0; beat < (128 / SIDEBAND_WIDTH); beat++) begin
          msg[beat * SIDEBAND_WIDTH +: SIDEBAND_WIDTH] = rdi.lpCfg;
          @(posedge clock);
        end
      end
    end
  endtask

  initial begin
    logic [127:0] msg;
    logic [127:0] rsp_msg;
    int cycle;
    int delayed_rsp_cycles;
    int msg_kind;
    bit saw_bad_state_while_waiting;
    bit got_rsp;

    rounds = 5;
    max_gap_cycles = 10;
    max_rsp_delay_cycles = 20;
    no_timeout_window_cycles = 40;

    if ($value$plusargs("D2D_SB_FUZZ_ROUNDS=%d", rounds) && rounds < 1) begin
      rounds = 1;
    end
    if ($value$plusargs("D2D_SB_FUZZ_MAX_GAP=%d", max_gap_cycles) && max_gap_cycles < 0) begin
      max_gap_cycles = 0;
    end
    if ($value$plusargs("D2D_SB_FUZZ_MAX_RSP_DELAY=%d", max_rsp_delay_cycles) && max_rsp_delay_cycles < 0) begin
      max_rsp_delay_cycles = 0;
    end
    if ($value$plusargs("D2D_SB_FUZZ_NO_TIMEOUT_WINDOW=%d", no_timeout_window_cycles) && no_timeout_window_cycles < 1) begin
      no_timeout_window_cycles = 1;
    end

    @(negedge reset);
    complete_active_bringup();
    wait_fdi_state(RDI_STATE_ACTIVE, DEFAULT_WAIT_CYCLES);

    // Active-state sideband timing fuzzer:
    // inject legal messages with randomized spacing, then optionally consume
    // a bounded response window if DUT emits sideband.
    for (i = 0; i < rounds; i++) begin
      wait_rand_gap(max_gap_cycles);

      msg_kind = $urandom_range(0, 2);
      case (msg_kind)
        0: send_rdi_sideband_msg(sb_adapter0_req_active());
        1: send_rdi_sideband_msg(sb_advcap_adapter_stall());
        default: send_rdi_sideband_msg(sb_advcap_adapter());
      endcase

      delayed_rsp_cycles = $urandom_range(0, max_rsp_delay_cycles);
      try_recv_rdi_sideband_msg(got_rsp, rsp_msg, delayed_rsp_cycles);
      if (got_rsp) begin
        if (!sb_is_adapter0_rsp_active(rsp_msg) &&
            !sb_is_adapter0_rsp_linkreset(rsp_msg) &&
            !sb_is_adapter0_rsp_disabled(rsp_msg) &&
            !sb_is_advcap_adapter(rsp_msg) &&
            !sb_is_advcap_adapter_stall(rsp_msg)) begin
          $fatal(1, "Observed unexpected sideband response during fuzz: 0x%032h", rsp_msg);
        end
      end

      // Keep a bounded window after each exchange and ensure no timeout-driven collapse.
      saw_bad_state_while_waiting = 1'b0;
      for (cycle = 0; cycle < no_timeout_window_cycles; cycle++) begin
        @(posedge clock);
        if (fdi.plStateSts == RDI_STATE_LINKERROR) begin
          saw_bad_state_while_waiting = 1'b1;
        end
      end
      if (saw_bad_state_while_waiting) begin
        $fatal(1, "FDI reached LinkError during active sideband fuzz round %0d", i);
      end
    end

    if (fdi.plStateSts != RDI_STATE_ACTIVE) begin
      $fatal(1, "FDI not Active at end of sideband timing fuzzer");
    end

    $display(
      "D2D sideband_timing_fuzzer completed: rounds=%0d max_gap=%0d max_rsp_delay=%0d no_timeout_window=%0d",
      rounds, max_gap_cycles, max_rsp_delay_cycles, no_timeout_window_cycles
    );
    $finish;
  end

endmodule
