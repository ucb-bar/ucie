module ucie_d2d_test (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);
  import ucie_d2d_dv_pkg::*;
  `include "ucie_d2d_test_tasks.svh"

  bit saw_rdi_active;

  always @(posedge clock) begin
    if (reset) begin
      saw_rdi_active <= 1'b0;
    end else if (rdi.plStateSts == RDI_STATE_ACTIVE) begin
      saw_rdi_active <= 1'b1;
    end

    if (!reset && fdi.plStateSts == RDI_STATE_ACTIVE && !saw_rdi_active) begin
      $fatal(1, "FDI reached Active before RDI reported Active");
    end
  end

  initial begin
    bring_link_to_active();
    $display("D2D active_ordering completed");
    $finish;
  end
endmodule
