module ucie_d2d_test (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);
  import ucie_d2d_dv_pkg::*;
  `include "ucie_d2d_test_tasks.svh"

  int rounds;
  int i;
  int settle_cycles;

  int rx_active_ack_delay;
  int stall_ack_delay;
  bit hold_stall_ack;

  task automatic wait_rand_cycles(input int max_cycles);
    int d;
    begin
      if (max_cycles <= 0) begin
        return;
      end
      d = $urandom_range(0, max_cycles);
      wait_cycles(d);
    end
  endtask

  always @(posedge clock) begin
    if (reset) begin
      rx_active_ack_delay <= -1;
      stall_ack_delay <= -1;
      hold_stall_ack <= 1'b0;
    end else begin
      // Reactive lpRxActiveSts driving:
      // when DUT asks for RX active, acknowledge after small random delay.
      if (fdi.plRxActiveReq && !fdi.lpRxActiveSts && rx_active_ack_delay < 0) begin
        rx_active_ack_delay <= $urandom_range(0, 3);
      end
      if (rx_active_ack_delay == 0) begin
        fdi.lpRxActiveSts <= 1'b1;
        rx_active_ack_delay <= -1;
      end else if (rx_active_ack_delay > 0) begin
        rx_active_ack_delay <= rx_active_ack_delay - 1;
      end
      if (!fdi.plRxActiveReq) begin
        fdi.lpRxActiveSts <= 1'b0;
      end

      // Reactive stall handshake:
      // when DUT requests stall, acknowledge after random short delay.
      if (fdi.plStallReq && !hold_stall_ack && stall_ack_delay < 0) begin
        stall_ack_delay <= $urandom_range(0, 4);
      end
      if (stall_ack_delay == 0) begin
        fdi.lpStallAck <= 1'b1;
        hold_stall_ack <= 1'b1;
        stall_ack_delay <= -1;
      end else if (stall_ack_delay > 0) begin
        stall_ack_delay <= stall_ack_delay - 1;
      end
      if (!fdi.plStallReq) begin
        fdi.lpStallAck <= 1'b0;
        hold_stall_ack <= 1'b0;
      end

      if ($isunknown(fdi.plStateSts) || $isunknown(rdi.plStateSts) ||
          $isunknown(fdi.plStallReq) || $isunknown(fdi.plRxActiveReq)) begin
        $fatal(1, "Unknown detected on race-test state/handshake signals");
      end
    end
  end

  initial begin
    int local_kind;
    int remote_kind;
    int order_kind;
    int cycle;
    bit saw_fdi_linkerror;
    bit saw_illegal_state;

    rounds = 8;
    settle_cycles = 70;

    if ($value$plusargs("D2D_RE_RACE_ROUNDS=%d", rounds) && rounds < 1) begin
      rounds = 1;
    end
    if ($value$plusargs("D2D_RE_RACE_SETTLE=%d", settle_cycles) && settle_cycles < 20) begin
      settle_cycles = 20;
    end

    @(negedge reset);
    complete_active_bringup();
    wait_fdi_state(RDI_STATE_ACTIVE, DEFAULT_WAIT_CYCLES);

    // Ensure baseline control values.
    fdi.lpStateReq = RDI_STATE_REQ_NOP;
    rdi.drive_state(RDI_STATE_ACTIVE);
    rdi.drive_inband_present();
    fdi.lpRxActiveSts = 1'b1;
    fdi.lpStallAck = 1'b0;

    for (i = 0; i < rounds; i++) begin
      // Re-arm local controls each round. Do not force a strict return-to-active
      // baseline here; race interactions may legitimately remain in another
      // non-active state without a dedicated recovery sequence.
      fdi.lpStateReq = RDI_STATE_REQ_NOP;
      rdi.drive_inband_present();
      wait_rand_cycles(8);

      // Random local request kind.
      local_kind = $urandom_range(0, 3);
      case (local_kind)
        0: fdi.lpStateReq = RDI_STATE_REQ_RETRAIN;
        1: fdi.lpStateReq = RDI_STATE_REQ_LINKRESET;
        2: fdi.lpStateReq = RDI_STATE_REQ_DISABLED;
        default: fdi.lpStateReq = RDI_STATE_REQ_NOP;
      endcase

      // Random remote state perturbation kind.
      remote_kind = $urandom_range(0, 2);
      // 0: RETRAIN, 1: LINKERROR, 2: stay ACTIVE.

      order_kind = $urandom_range(0, 1);
      if (order_kind == 0) begin
        wait_rand_cycles(5);
      end

      case (remote_kind)
        0: rdi.drive_state(RDI_STATE_RETRAIN);
        1: rdi.drive_state(RDI_STATE_LINKERROR);
        default: rdi.drive_state(RDI_STATE_ACTIVE);
      endcase

      if (order_kind == 1) begin
        wait_rand_cycles(5);
      end

      saw_fdi_linkerror = 1'b0;
      saw_illegal_state = 1'b0;
      for (cycle = 0; cycle < settle_cycles; cycle++) begin
        @(posedge clock);
        if (fdi.plStateSts == RDI_STATE_LINKERROR) begin
          saw_fdi_linkerror = 1'b1;
        end
        if (fdi.plStateSts != RDI_STATE_ACTIVE &&
            fdi.plStateSts != RDI_STATE_RETRAIN &&
            fdi.plStateSts != RDI_STATE_LINKRESET &&
            fdi.plStateSts != RDI_STATE_DISABLED &&
            fdi.plStateSts != RDI_STATE_LINKERROR) begin
          saw_illegal_state = 1'b1;
        end
      end

      if (saw_illegal_state) begin
        $fatal(1, "Observed illegal FDI state encoding during race round %0d", i);
      end

      // Strong check: when remote forces LinkError in this race, FDI must converge to LinkError.
      if (remote_kind == 1 && !saw_fdi_linkerror) begin
        $fatal(1, "FDI did not converge to LinkError in race round %0d", i);
      end
    end

    // Cleanup controls.
    fdi.lpStateReq = RDI_STATE_REQ_NOP;
    rdi.drive_inband_present();

    $display("D2D retrain_error_race_random completed: rounds=%0d settle_cycles=%0d", rounds, settle_cycles);
    $finish;
  end

endmodule
