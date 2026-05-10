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
      if (fdi.plValid) begin
        $fatal(1, "FDI plValid asserted before FDI reached Active");
      end
      if (rdi.lpValid) begin
        $fatal(1, "RDI lpValid asserted before FDI reached Active");
      end
    end
  end

  initial begin
    @(negedge reset) begin
    end

    fdi.lpValid = 1'b1;
    fdi.lpIrdy = 1'b1;
    fdi.lpData = {{(DATA_BITS-32){1'b0}}, 32'hace0_0001};

    rdi.plValid = 1'b1;
    rdi.plData = {{(DATA_BITS-32){1'b0}}, 32'hace0_0002};

    complete_active_bringup();
    wait_cycles(5);

    $display("D2D no_early_data completed");
    $finish;
  end
endmodule
