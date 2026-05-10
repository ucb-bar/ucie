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

    bring_link_to_active();

    if (fdi.plStateSts !== RDI_STATE_ACTIVE || rdi.plStateSts !== RDI_STATE_ACTIVE) begin
      $fatal(1, "LinkError disabled-priority test did not start from Active");
    end

    fdi.lpStateReq = RDI_STATE_REQ_DISABLED;
    @(posedge clock);
    rdi.drive_state(RDI_STATE_LINKERROR);

    for (cycle = 0; cycle < DEFAULT_WAIT_CYCLES && fdi.plStateSts !== RDI_STATE_LINKERROR; cycle++) begin
      @(posedge clock);
      if (fdi.plStateSts === RDI_STATE_DISABLED) begin
        $fatal(1, "FDI entered Disabled instead of prioritizing LinkError");
      end
      if (fdi.plStateSts === RDI_STATE_LINKRESET) begin
        $fatal(1, "FDI entered LinkReset instead of prioritizing LinkError");
      end
      if (rdi.plStateSts !== RDI_STATE_LINKERROR) begin
        $fatal(1, "RDI left LinkError before FDI observed LinkError during Disabled competition");
      end
    end

    if (fdi.plStateSts !== RDI_STATE_LINKERROR) begin
      $fatal(1, "FDI did not enter LinkError during Disabled competition");
    end

    $display("D2D linkerror_disabled_priority completed");
    $finish;
  end
endmodule
