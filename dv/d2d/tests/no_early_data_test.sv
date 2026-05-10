module ucie_d2d_test (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);
  import ucie_d2d_dv_pkg::*;
  `include "ucie_d2d_test_tasks.svh"

  always @(posedge clock) begin
    if (!reset && fdi.plStateSts != RDI_STATE_ACTIVE) begin
      if (fdi.plValid || rdi.lpValid) begin
        $fatal(1, "Data valid asserted before FDI reached Active");
      end
    end
  end

  initial begin
    @(negedge reset);

    fdi.lpValid = 1'b1;
    fdi.lpIrdy = 1'b1;
    fdi.lpData = 'hace0_0001;

    rdi.plValid = 1'b1;
    rdi.plData = 'hace0_0002;

    complete_active_bringup();
    wait_cycles(5);

    $display("D2D no_early_data completed");
    $finish;
  end
endmodule
