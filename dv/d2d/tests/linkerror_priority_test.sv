module ucie_d2d_test (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);
  import ucie_d2d_dv_pkg::*;
  `include "ucie_d2d_test_tasks.svh"

  task automatic recover_to_active();
    fdi.lpStateReq = RDI_STATE_REQ_NOP;
    fdi.lpRxActiveSts = 1'b0;
    wait_fdi_state(RDI_STATE_RESET, DEFAULT_WAIT_CYCLES);
    rdi.drive_state(RDI_STATE_RESET);
    complete_active_bringup();
  endtask

  task automatic expect_linkerror_priority(input logic [3:0] competing_req, input string competing_name);
    int cycle;

    if (fdi.plStateSts !== RDI_STATE_ACTIVE || rdi.plStateSts !== RDI_STATE_ACTIVE) begin
      $fatal(1, "LinkError priority test for %s did not start from Active", competing_name);
    end

    fdi.lpStateReq = competing_req;
    @(posedge clock);
    rdi.drive_state(RDI_STATE_LINKERROR);

    for (cycle = 0; cycle < DEFAULT_WAIT_CYCLES && fdi.plStateSts !== RDI_STATE_LINKERROR; cycle++) begin
      @(posedge clock);
      if (fdi.plStateSts === RDI_STATE_LINKRESET || fdi.plStateSts === RDI_STATE_DISABLED) begin
        $fatal(1, "FDI entered %s instead of prioritizing LinkError", rdi_state_name(fdi.plStateSts));
      end
      if (rdi.plStateSts !== RDI_STATE_LINKERROR) begin
        $fatal(1, "RDI left LinkError before FDI observed LinkError during %s competition", competing_name);
      end
    end

    if (fdi.plStateSts !== RDI_STATE_LINKERROR) begin
      $fatal(1, "FDI did not enter LinkError during %s competition", competing_name);
    end

    if (rdi.plStateSts !== RDI_STATE_LINKERROR) begin
      $fatal(1, "FDI LinkError was observed after RDI left LinkError during %s competition", competing_name);
    end

    fdi.lpStateReq = RDI_STATE_REQ_NOP;
  endtask

  initial begin
    bring_link_to_active();

    expect_linkerror_priority(RDI_STATE_REQ_LINKRESET, "LinkReset");
    $display("D2D linkerror_priority linkreset competition completed");

    recover_to_active();
    expect_linkerror_priority(RDI_STATE_REQ_DISABLED, "Disabled");

    $display("D2D linkerror_priority completed");
    $finish;
  end
endmodule
