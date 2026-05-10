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

    rdi.plStallReq = 1'b1;

    while (!rdi.lpStallAck) begin
      @(posedge clock);
    end

    if (!rdi.plStallReq) begin
      $fatal(1, "RDI stall acknowledge asserted after stall request dropped");
    end

    rdi.plStallReq = 1'b0;
    wait_cycles(4);

    if (rdi.lpStallAck) begin
      $fatal(1, "RDI stall acknowledge did not clear after request dropped");
    end

    $display("D2D stall_handshake completed");
    $finish;
  end
endmodule
