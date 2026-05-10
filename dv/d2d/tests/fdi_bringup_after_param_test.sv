module ucie_d2d_test (
  input logic clock,
  input logic reset,
  ucie_d2d_fdi_if fdi,
  ucie_d2d_rdi_if rdi
);
  import ucie_d2d_dv_pkg::*;
  `include "ucie_d2d_test_tasks.svh"

  task automatic expect_no_fdi_release(input string phase);
    if (fdi.plStateSts === RDI_STATE_ACTIVE) begin
      $fatal(1, "FDI reached Active before bring-up completed during %s", phase);
    end
    if (fdi.plValid || rdi.lpValid) begin
      $fatal(1, "Mainband data was released before FDI Active during %s", phase);
    end
  endtask

  initial begin
    logic [127:0] msg;

    @(negedge reset);

    fdi.lpValid = 1'b1;
    fdi.lpIrdy = 1'b1;
    fdi.lpData = {{(DATA_BITS-32){1'b0}}, 32'hf0ad_0001};
    rdi.plValid = 1'b1;
    rdi.plData = {{(DATA_BITS-32){1'b0}}, 32'hf0ad_0002};

    rdi.drive_inband_present();
    wait_cycles(5);
    expect_no_fdi_release("before RDI Active");
    if (fdi.plInbandPres || fdi.plRxActiveReq) begin
      $fatal(1, "FDI bring-up indication asserted before RDI Active");
    end

    rdi.drive_state(RDI_STATE_ACTIVE);

    recv_rdi_sideband_msg(msg, DEFAULT_WAIT_CYCLES);
    if (!sb_is_advcap_adapter(msg)) begin
      $fatal(1, "Expected adapter ADV_CAP after RDI Active, got 0x%032h", msg);
    end
    expect_no_fdi_release("before remote ADV_CAP");
    if (fdi.plInbandPres || fdi.plRxActiveReq) begin
      $fatal(1, "FDI bring-up indication asserted before parameter exchange completed");
    end

    send_rdi_sideband_msg(sb_advcap_adapter());
    wait_fdi_inband_present(DEFAULT_WAIT_CYCLES);
    expect_no_fdi_release("after parameter exchange before Active request");
    if (fdi.plRxActiveReq) begin
      $fatal(1, "FDI plRxActiveReq asserted before remote REQ_ACTIVE");
    end

    send_rdi_sideband_msg(sb_adapter0_req_active());
    wait_fdi_rx_active_req(DEFAULT_WAIT_CYCLES);
    expect_no_fdi_release("after remote REQ_ACTIVE before protocol acknowledgement");

    fdi.lpRxActiveSts = 1'b1;
    recv_rdi_sideband_msg(msg, DEFAULT_WAIT_CYCLES);
    if (!sb_is_adapter0_rsp_active(msg)) begin
      $fatal(1, "Expected adapter RSP_ACTIVE, got 0x%032h", msg);
    end
    expect_no_fdi_release("after local RSP_ACTIVE before remote RSP_ACTIVE");

    send_rdi_sideband_msg(sb_adapter0_rsp_active());
    wait_fdi_state(RDI_STATE_ACTIVE, DEFAULT_WAIT_CYCLES);

    if (!fdi.plInbandPres || !fdi.plRxActiveReq) begin
      $fatal(1, "FDI Active reached without inband presence and RX active request");
    end

    wait_cycles(5);
    if (!rdi.lpValid) begin
      $fatal(1, "FDI-to-RDI mainband data did not release after FDI Active");
    end
    if (!fdi.plValid) begin
      $fatal(1, "RDI-to-FDI mainband data did not release after FDI Active");
    end

    $display("D2D fdi_bringup_after_param completed");
    $finish;
  end
endmodule
