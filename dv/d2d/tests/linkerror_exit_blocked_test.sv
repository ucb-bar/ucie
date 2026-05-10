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
    bit saw_fdi_linkerror;
    bit saw_fdi_exit_from_linkerror;

    bring_link_to_active();

    if (fdi.plStateSts !== RDI_STATE_ACTIVE || rdi.plStateSts !== RDI_STATE_ACTIVE) begin
      $fatal(1, "LinkError exit-block test did not start from Active");
    end

    // Request recovery at the same time LinkError is injected to verify
    // there is no early LinkError exit before FDI has entered LinkError.
    fdi.lpStateReq = RDI_STATE_REQ_ACTIVE;
    fdi.lpRxActiveSts = 1'b0;
    rdi.drive_state(RDI_STATE_LINKERROR);

    for (cycle = 0; cycle < DEFAULT_WAIT_CYCLES; cycle++) begin
      @(posedge clock);

      if (fdi.plStateSts === RDI_STATE_LINKERROR) begin
        saw_fdi_linkerror = 1'b1;
      end

      if (saw_fdi_linkerror && fdi.plStateSts !== RDI_STATE_LINKERROR) begin
        saw_fdi_exit_from_linkerror = 1'b1;
      end

      if (!saw_fdi_linkerror && fdi.plStateSts !== RDI_STATE_ACTIVE && fdi.plStateSts !== RDI_STATE_LINKERROR) begin
        $fatal(1, "FDI transitioned to %s before entering LinkError", rdi_state_name(fdi.plStateSts));
      end

      if (saw_fdi_linkerror && saw_fdi_exit_from_linkerror) begin
        break;
      end
    end

    if (!saw_fdi_linkerror) begin
      $fatal(1, "FDI did not enter LinkError");
    end

    if (!saw_fdi_exit_from_linkerror) begin
      $fatal(1, "FDI never exited LinkError after entry when recovery was requested");
    end

    $display("D2D linkerror_exit_blocked completed");
    $finish;
  end
endmodule
