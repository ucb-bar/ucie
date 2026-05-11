module ucie_d2d_test (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);
  import ucie_d2d_dv_pkg::*;
  `include "ucie_d2d_test_tasks.svh"

  task automatic expect_fdi_not_active(input string phase);
    if (fdi.plStateSts === RDI_STATE_ACTIVE) begin
      $fatal(1, "FDI reached Active too early during %s", phase);
    end
  endtask

  task automatic wait_pre_rdi_active_quiet(input int cycles);
    repeat (cycles) begin
      @(posedge clock);
      expect_fdi_not_active("RDI below Active");
      if (rdi.lpCfgVld === 1'b1) begin
        $fatal(1, "Adapter started sideband parameter exchange before RDI Active");
      end
      if (fdi.plInbandPres === 1'b1 || fdi.plRxActiveReq === 1'b1 || fdi.plProtocolVld === 1'b1) begin
        $fatal(1, "Adapter exposed protocol-visible bring-up status before RDI Active");
      end
    end
  endtask

  always @(posedge clock) begin
    if (!reset && fdi.plStateSts == RDI_STATE_ACTIVE && rdi.plStateSts != RDI_STATE_ACTIVE) begin
      $fatal(
        1,
        "FDI reached Active while RDI was %s",
        rdi_state_name(rdi.plStateSts)
      );
    end
  end

  initial begin
    logic [127:0] msg;

    @(negedge reset);

    // Adapter initialization must be gated by RDI Active.
    rdi.drive_inband_present();
    wait_pre_rdi_active_quiet(10);

    rdi.drive_state(RDI_STATE_ACTIVE);

    recv_rdi_sideband_msg(msg, DEFAULT_WAIT_CYCLES);
    if (!sb_is_advcap_adapter(msg)) begin
      $fatal(1, "Expected adapter ADV_CAP after RDI Active, got 0x%032h", msg);
    end
    expect_fdi_not_active("local ADV_CAP only");

    send_rdi_sideband_msg(sb_advcap_adapter());
    wait_fdi_inband_present(DEFAULT_WAIT_CYCLES);
    expect_fdi_not_active("parameter exchange complete before Active request");

    send_rdi_sideband_msg(sb_adapter0_req_active());
    wait_fdi_rx_active_req(DEFAULT_WAIT_CYCLES);
    expect_fdi_not_active("remote REQ_ACTIVE before protocol RX acknowledgement");

    fdi.lpRxActiveSts = 1'b1;
    recv_rdi_sideband_msg(msg, DEFAULT_WAIT_CYCLES);
    if (!sb_is_adapter0_rsp_active(msg)) begin
      $fatal(1, "Expected adapter RSP_ACTIVE, got 0x%032h", msg);
    end
    expect_fdi_not_active("local RSP_ACTIVE before remote RSP_ACTIVE");

    send_rdi_sideband_msg(sb_adapter0_rsp_active());
    wait_fdi_state(RDI_STATE_ACTIVE, DEFAULT_WAIT_CYCLES);

    $display("D2D active_ordering completed");
    $finish;
  end
endmodule
