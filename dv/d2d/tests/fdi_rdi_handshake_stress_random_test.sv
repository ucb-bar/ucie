module ucie_d2d_test (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);
  import ucie_d2d_dv_pkg::*;
  `include "ucie_d2d_test_tasks.svh"

  int total_cycles;
  int i;
  int in_accepts;
  int out_accepts;
  int active_cycles;
  int state_hold;

  function automatic logic [DATA_BITS-1:0] rand_data();
    logic [DATA_BITS-1:0] d;
    int k;
    begin
      d = '0;
      for (k = 0; k < DATA_BITS; k += 32) begin
        d[k +: 32] = $urandom();
      end
      return d;
    end
  endfunction

  task automatic randomize_inputs_and_state();
    int req_sel;
    begin
      // Traffic and backpressure randomization.
      fdi.lpValid = ($urandom_range(0, 99) < 70);
      fdi.lpIrdy = ($urandom_range(0, 99) < 85);
      fdi.lpData = rand_data();
      rdi.plTrdy = ($urandom_range(0, 99) < 80);

      // Keep bring-up completion acknowledged once established.
      fdi.lpRxActiveSts = 1'b1;
      fdi.lpStallAck = ($urandom_range(0, 99) < 50) ? rdi.plStallReq : 1'b0;

      // Random state request from FDI side (local requester).
      req_sel = $urandom_range(0, 99);
      if (req_sel < 70) begin
        fdi.lpStateReq = RDI_STATE_REQ_NOP;
      end else if (req_sel < 80) begin
        fdi.lpStateReq = RDI_STATE_REQ_ACTIVE;
      end else if (req_sel < 88) begin
        fdi.lpStateReq = RDI_STATE_REQ_RETRAIN;
      end else if (req_sel < 94) begin
        fdi.lpStateReq = RDI_STATE_REQ_LINKRESET;
      end else begin
        fdi.lpStateReq = RDI_STATE_REQ_DISABLED;
      end

      // Randomized RDI state perturbation windows to stress gating.
      if (state_hold == 0) begin
        if ($urandom_range(0, 99) < 12) begin
          case ($urandom_range(0, 3))
            0: rdi.plStateSts = RDI_STATE_RETRAIN;
            1: rdi.plStateSts = RDI_STATE_LINKRESET;
            2: rdi.plStateSts = RDI_STATE_DISABLED;
            default: rdi.plStateSts = RDI_STATE_LINKERROR;
          endcase
          state_hold = $urandom_range(2, 10);
          rdi.plInbandPres = 1'b0;
        end else begin
          rdi.plStateSts = RDI_STATE_ACTIVE;
          rdi.plInbandPres = 1'b1;
        end
      end else begin
        state_hold--;
        if (state_hold == 0) begin
          rdi.plStateSts = RDI_STATE_ACTIVE;
          rdi.plInbandPres = 1'b1;
        end
      end
    end
  endtask

  always @(posedge clock) begin
    if (!reset) begin
      if ($isunknown(fdi.plValid) || $isunknown(fdi.plTrdy) || $isunknown(fdi.plStateSts) ||
          $isunknown(fdi.plInbandPres) || $isunknown(rdi.plStateSts) || $isunknown(rdi.plTrdy)) begin
        $fatal(1, "Unknown detected on handshake/state signals during stress run");
      end

      if (fdi.lpValid && fdi.lpIrdy && fdi.plTrdy) begin
        in_accepts++;
      end

      if (fdi.plValid && fdi.lpIrdy) begin
        if (fdi.plStateSts != RDI_STATE_ACTIVE || fdi.plInbandPres != 1'b1) begin
          $fatal(
            1,
            "Output traffic accepted outside ACTIVE/inband: fdi_state=%s inband=%0b rdi_state=%s",
            rdi_state_name(fdi.plStateSts),
            fdi.plInbandPres,
            rdi_state_name(rdi.plStateSts)
          );
        end
        out_accepts++;
      end

      if (fdi.plStateSts == RDI_STATE_ACTIVE) begin
        active_cycles++;
      end
    end
  end

  initial begin
    total_cycles = 600;
    if (!$value$plusargs("D2D_RANDOM_CYCLES=%d", total_cycles)) begin
      total_cycles = 600;
    end

    in_accepts = 0;
    out_accepts = 0;
    active_cycles = 0;
    state_hold = 0;

    @(negedge reset) begin
    end
    complete_active_bringup();
    wait_cycles(10);

    for (i = 0; i < total_cycles; i++) begin
      @(negedge clock) begin
        randomize_inputs_and_state();
      end
      @(posedge clock) begin
      end
    end

    // Return to a clean active state before concluding.
    @(negedge clock) begin
      rdi.plStateSts = RDI_STATE_ACTIVE;
      rdi.plInbandPres = 1'b1;
      fdi.lpStateReq = RDI_STATE_REQ_NOP;
      fdi.lpValid = 1'b0;
      fdi.lpIrdy = 1'b0;
      rdi.plTrdy = 1'b1;
    end
    wait_cycles(10);

    if (active_cycles < (total_cycles / 3)) begin
      $fatal(1, "Stress run spent too little time in ACTIVE: active_cycles=%0d total=%0d", active_cycles, total_cycles);
    end
    if (in_accepts == 0) begin
      $fatal(1, "No input handshakes were accepted during stress run");
    end

    $display(
      "D2D FDI/RDI handshake stress random completed: cycles=%0d in_accepts=%0d out_accepts=%0d active_cycles=%0d",
      total_cycles, in_accepts, out_accepts, active_cycles
    );
    $finish;
  end

endmodule
