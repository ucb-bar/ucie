task automatic wait_cycles(input int cycles);
  repeat (cycles) begin
    @(posedge clock) begin
    end
  end
endtask

task automatic wait_fdi_state(input logic [3:0] state, input int max_cycles);
  int cycle;

  for (cycle = 0; cycle < max_cycles; cycle++) begin
    if (fdi.plStateSts === state) begin
      return;
    end
    @(posedge clock) begin
    end
  end

  $fatal(1, "Timed out waiting for FDI state %s", rdi_state_name(state));
endtask

task automatic wait_fdi_rx_active_req(input int max_cycles);
  int cycle;

  for (cycle = 0; cycle < max_cycles; cycle++) begin
    if (fdi.plRxActiveReq === 1'b1) begin
      return;
    end
    @(posedge clock) begin
    end
  end

  $fatal(
    1,
    "Timed out waiting for FDI plRxActiveReq; fdi_state=%s fdi_inband=%0b rdi_lpStateReq=0x%0h",
    rdi_state_name(fdi.plStateSts),
    fdi.plInbandPres,
    rdi.lpStateReq
  );
endtask

task automatic wait_fdi_inband_present(input int max_cycles);
  int cycle;

  for (cycle = 0; cycle < max_cycles; cycle++) begin
    if (fdi.plInbandPres === 1'b1) begin
      return;
    end
    @(posedge clock) begin
    end
  end

  $fatal(
    1,
    "Timed out waiting for FDI plInbandPres; fdi_state=%s rdi_lpStateReq=0x%0h",
    rdi_state_name(fdi.plStateSts),
    rdi.lpStateReq
  );
endtask

task automatic expect_fdi_state(input logic [3:0] state);
  if (fdi.plStateSts != state) begin
    $fatal(
      1,
      "Expected FDI state %s, got %s",
      rdi_state_name(state),
      rdi_state_name(fdi.plStateSts)
    );
  end
endtask

task automatic send_rdi_sideband_msg(input logic [127:0] msg);
  int beat;

  rdi.plCfgVld = 1'b1;
  for (beat = 0; beat < (128 / SIDEBAND_WIDTH); beat++) begin
    rdi.plCfg = msg[beat * SIDEBAND_WIDTH +: SIDEBAND_WIDTH];
    @(posedge clock) begin
    end
  end
  rdi.plCfgVld = 1'b0;
  rdi.plCfg = '0;
endtask

task automatic recv_rdi_sideband_msg(output logic [127:0] msg, input int max_cycles);
  int beat;
  int cycle;

  msg = '0;
  for (cycle = 0; cycle < max_cycles && rdi.lpCfgVld !== 1'b1; cycle++) begin
    @(posedge clock) begin
    end
  end

  if (rdi.lpCfgVld !== 1'b1) begin
    $fatal(1, "Timed out waiting for RDI sideband output");
  end

  for (beat = 0; beat < (128 / SIDEBAND_WIDTH); beat++) begin
    msg[beat * SIDEBAND_WIDTH +: SIDEBAND_WIDTH] = rdi.lpCfg;
    @(posedge clock) begin
    end
  end
endtask

task automatic complete_active_bringup();
  logic [127:0] msg;

  rdi.drive_inband_present();
  wait_cycles(5);

  rdi.drive_state(RDI_STATE_ACTIVE);

  $display("D2D bring-up: waiting for adapter ADV_CAP");
  recv_rdi_sideband_msg(msg, DEFAULT_WAIT_CYCLES);
  $display("D2D bring-up: received adapter ADV_CAP 0x%032h", msg);
  if (!sb_is_advcap_adapter(msg)) begin
    $fatal(1, "Expected adapter ADV_CAP sideband message, got 0x%032h", msg);
  end

  $display("D2D bring-up: sending remote ADV_CAP");
  msg = sb_advcap_adapter();
  $display("D2D bring-up: sending remote ADV_CAP 0x%032h", msg);
  send_rdi_sideband_msg(msg);

  $display("D2D bring-up: waiting for FDI inband presence");
  wait_fdi_inband_present(DEFAULT_WAIT_CYCLES);

  $display("D2D bring-up: sending remote REQ_ACTIVE");
  msg = sb_adapter0_req_active();
  $display("D2D bring-up: sending remote REQ_ACTIVE 0x%032h", msg);
  send_rdi_sideband_msg(msg);

  $display("D2D bring-up: waiting for FDI plRxActiveReq");
  wait_fdi_rx_active_req(DEFAULT_WAIT_CYCLES);
  fdi.lpRxActiveSts = 1'b1;

  $display("D2D bring-up: waiting for adapter RSP_ACTIVE");
  recv_rdi_sideband_msg(msg, DEFAULT_WAIT_CYCLES);
  $display("D2D bring-up: received adapter RSP_ACTIVE 0x%032h", msg);
  if (!sb_is_adapter0_rsp_active(msg)) begin
    $fatal(1, "Expected adapter RSP_ACTIVE sideband message, got 0x%032h", msg);
  end

  $display("D2D bring-up: sending remote RSP_ACTIVE");
  msg = sb_adapter0_rsp_active();
  $display("D2D bring-up: sending remote RSP_ACTIVE 0x%032h", msg);
  send_rdi_sideband_msg(msg);
  wait_fdi_state(RDI_STATE_ACTIVE, DEFAULT_WAIT_CYCLES);
endtask

task automatic bring_link_to_active();
  @(negedge reset) begin
  end
  complete_active_bringup();
endtask
