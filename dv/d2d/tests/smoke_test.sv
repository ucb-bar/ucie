module ucie_d2d_test (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);
  import ucie_d2d_dv_pkg::*;

  initial begin
    @(negedge reset);

    rdi.drive_inband_present();
    repeat (5) begin
      @(posedge clock);
    end

    rdi.drive_state(RDI_STATE_ACTIVE);
    repeat (20) begin
      @(posedge clock);
    end

    $display("D2D DV smoke completed");
    $finish;
  end
  
endmodule
