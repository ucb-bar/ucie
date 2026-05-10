task automatic wait_cycles(input int cycles);
  repeat (cycles) begin
    @(posedge clock) begin
    end
  end
endtask

task automatic wait_fdi_state(input logic [3:0] state, input int max_cycles);
  int cycle;

  for (cycle = 0; cycle < max_cycles; cycle++) begin
    if (fdi.plStateSts == state) begin
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
    if (fdi.plRxActiveReq) begin
      return;
    end
    @(posedge clock) begin
    end
  end

  $fatal(1, "Timed out waiting for FDI plRxActiveReq");
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

task automatic complete_active_bringup();
  logic [127:0] msg;

  rdi.drive_inband_present();
  wait_cycles(5);

  rdi.drive_state(RDI_STATE_ACTIVE);

  rdi.recv_sideband_msg(msg, DEFAULT_WAIT_CYCLES);
  if (!sb_is_advcap_adapter(msg)) begin
    $fatal(1, "Expected adapter ADV_CAP sideband message, got 0x%032h", msg);
  end

  rdi.send_sideband_msg(sb_advcap_adapter());
  wait_cycles(8);

  rdi.send_sideband_msg(sb_adapter0_req_active());

  wait_fdi_rx_active_req(DEFAULT_WAIT_CYCLES);
  fdi.lpRxActiveSts = 1'b1;

  rdi.recv_sideband_msg(msg, DEFAULT_WAIT_CYCLES);
  if (!sb_is_adapter0_rsp_active(msg)) begin
    $fatal(1, "Expected adapter RSP_ACTIVE sideband message, got 0x%032h", msg);
  end

  rdi.send_sideband_msg(sb_adapter0_rsp_active());
  wait_fdi_state(RDI_STATE_ACTIVE, DEFAULT_WAIT_CYCLES);
endtask

task automatic bring_link_to_active();
  @(negedge reset) begin
  end
  complete_active_bringup();
endtask
