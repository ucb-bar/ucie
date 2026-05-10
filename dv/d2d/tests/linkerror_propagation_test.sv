module ucie_d2d_test (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);
  import ucie_d2d_dv_pkg::*;
  `include "ucie_d2d_test_tasks.svh"

  initial begin
    bring_link_to_active();

    rdi.drive_state(RDI_STATE_LINKERROR);
    wait_fdi_state(RDI_STATE_LINKERROR, DEFAULT_WAIT_CYCLES);

    if (fdi.plInbandPres) begin
      $fatal(1, "FDI inband presence stayed high in LinkError");
    end

    $display("D2D linkerror_propagation completed");
    $finish;
  end
endmodule
