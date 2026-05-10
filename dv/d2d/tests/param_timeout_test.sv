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
    int timeout_max_cycles;
    bit saw_linkerror;

    if (!$value$plusargs("D2D_PARAM_TIMEOUT_MAX_CYCLES=%d", timeout_max_cycles)) begin
      timeout_max_cycles = 700;
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

    // Do not send remote ADV_CAP, to force parameter-exchange timeout behavior.
    saw_linkerror = 1'b0;
    for (cycle = 0; cycle < timeout_max_cycles; cycle++) begin
      @(posedge clock);
      if (fdi.plStateSts === RDI_STATE_LINKERROR) begin
        saw_linkerror = 1'b1;
        break;
      end
    end

    if (!saw_linkerror) begin
      $fatal(
        1,
        "Parameter exchange timeout did not drive LinkError within %0d cycles after ADV_CAP start",
        timeout_max_cycles
      );
    end

    $display("D2D param_timeout completed");
    $finish;
  end
endmodule
