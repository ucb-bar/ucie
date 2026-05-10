module ucie_d2d_test (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);
  import ucie_d2d_dv_pkg::*;
  `include "ucie_d2d_test_tasks.svh"

  task automatic expect_no_stage3_activity(input string phase);
    if (rdi.lpCfgVld === 1'b1) begin
      $fatal(1, "Adapter sent Stage 3 sideband before RDI Active during %s", phase);
    end
    if (fdi.plInbandPres || fdi.plRxActiveReq || fdi.plProtocolVld) begin
      $fatal(1, "Adapter exposed FDI bring-up status before RDI Active during %s", phase);
    end
    if (fdi.plStateSts === RDI_STATE_ACTIVE) begin
      $fatal(1, "FDI reached Active before RDI Active during %s", phase);
    end
  endtask

  task automatic hold_rdi_below_active(input int cycles, input string phase);
    repeat (cycles) begin
      @(posedge clock);
      expect_no_stage3_activity(phase);
    end
  endtask

  initial begin
    logic [127:0] msg;

    @(negedge reset);

    hold_rdi_below_active(10, "RDI Reset without inband presence");

    rdi.drive_inband_present();
    hold_rdi_below_active(20, "RDI Reset with inband presence");

    rdi.drive_state(RDI_STATE_LINKRESET);
    hold_rdi_below_active(10, "RDI LinkReset");

    rdi.drive_state(RDI_STATE_RETRAIN);
    hold_rdi_below_active(10, "RDI Retrain before initialization");

    rdi.drive_state(RDI_STATE_ACTIVE);

    recv_rdi_sideband_msg(msg, DEFAULT_WAIT_CYCLES);
    if (!sb_is_advcap_adapter(msg)) begin
      $fatal(1, "Expected Stage 3 ADV_CAP after RDI Active, got 0x%032h", msg);
    end

    if (fdi.plStateSts === RDI_STATE_ACTIVE) begin
      $fatal(1, "FDI reached Active immediately after first Stage 3 ADV_CAP");
    end

    $display("D2D stage3_rdi_active_gate completed");
    $finish;
  end
endmodule
