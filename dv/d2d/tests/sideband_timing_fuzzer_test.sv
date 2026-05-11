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

  initial begin
    logic [127:0] msg;
    int cycle;
    int stall_injections;
    int delayed_rsp_cycles;
    bit saw_bad_state_while_waiting;

    rounds = 20;
    max_gap_cycles = 20;
    max_rsp_delay_cycles = 80;
    no_timeout_window_cycles = 120;

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
    rdi.drive_inband_present();
    wait_rand_gap(max_gap_cycles);
    rdi.drive_state(RDI_STATE_ACTIVE);

    // Bring-up fuzzer: randomized legal timing around parameter exchange.
    recv_rdi_sideband_msg(msg, DEFAULT_WAIT_CYCLES);
    if (!sb_is_advcap_adapter(msg)) begin
      $fatal(1, "Expected adapter ADV_CAP sideband message, got 0x%032h", msg);
    end

    stall_injections = $urandom_range(0, 2);
    for (i = 0; i < stall_injections; i++) begin
      wait_rand_gap(max_gap_cycles);
      send_rdi_sideband_msg(sb_advcap_adapter_stall());
    end

    wait_rand_gap(max_gap_cycles);
    send_rdi_sideband_msg(sb_advcap_adapter());
    wait_fdi_inband_present(DEFAULT_WAIT_CYCLES);

    wait_rand_gap(max_gap_cycles);
    send_rdi_sideband_msg(sb_adapter0_req_active());
    wait_fdi_rx_active_req(DEFAULT_WAIT_CYCLES);
    fdi.lpRxActiveSts = 1'b1;

    expect_rsp_active_from_adapter(DEFAULT_WAIT_CYCLES);

    // Bounded delayed-response window: hold remote RSP_ACTIVE for a while,
    // ensure no premature LinkError, then complete handshake.
    delayed_rsp_cycles = $urandom_range(0, max_rsp_delay_cycles);
    saw_bad_state_while_waiting = 1'b0;
    for (cycle = 0; cycle < delayed_rsp_cycles; cycle++) begin
      @(posedge clock);
      if (fdi.plStateSts == RDI_STATE_LINKERROR) begin
        saw_bad_state_while_waiting = 1'b1;
      end
    end
    if (saw_bad_state_while_waiting) begin
      $fatal(1, "FDI reached LinkError during bounded delayed RSP_ACTIVE window");
    end

    send_rdi_sideband_msg(sb_adapter0_rsp_active());
    wait_fdi_state(RDI_STATE_ACTIVE, DEFAULT_WAIT_CYCLES);

    // Active-state timing fuzzer: repeated remote REQ_ACTIVE with randomized spacing.
    for (i = 0; i < rounds; i++) begin
      wait_rand_gap(max_gap_cycles);
      send_rdi_sideband_msg(sb_adapter0_req_active());
      expect_rsp_active_from_adapter(DEFAULT_WAIT_CYCLES);

      // Keep a bounded window after each exchange and ensure no timeout-driven collapse.
      for (cycle = 0; cycle < no_timeout_window_cycles; cycle++) begin
        @(posedge clock);
        if (fdi.plStateSts == RDI_STATE_LINKERROR) begin
          $fatal(1, "FDI reached LinkError during active sideband fuzz round %0d", i);
        end
      end
    end

    if (fdi.plStateSts != RDI_STATE_ACTIVE) begin
      $fatal(1, "FDI not Active at end of sideband timing fuzzer");
    end

    $display(
      "D2D sideband_timing_fuzzer completed: rounds=%0d max_gap=%0d max_rsp_delay=%0d stall_injections=%0d",
      rounds, max_gap_cycles, max_rsp_delay_cycles, stall_injections
    );
    $finish;
  end

endmodule
