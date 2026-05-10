task automatic wait_cycles(input int cycles);
  repeat (cycles) begin
    @(posedge clock);
  end
endtask

task automatic wait_fdi_state(input logic [3:0] state, input int max_cycles);
  int cycle;

  for (cycle = 0; cycle < max_cycles; cycle++) begin
    if (fdi.plStateSts == state) begin
      return;
    end
    @(posedge clock);
  end

  $fatal(1, "Timed out waiting for FDI state %s", rdi_state_name(state));
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
  rdi.drive_inband_present();
  wait_cycles(5);

  rdi.drive_state(RDI_STATE_ACTIVE);
  wait_cycles(5);

  rdi.send_sideband_msg(sb_advcap_adapter());
  wait_cycles(8);

  rdi.send_sideband_msg(sb_adapter0_req_active());

  while (!fdi.plRxActiveReq) begin
    @(posedge clock);
  end
  fdi.lpRxActiveSts = 1'b1;

  rdi.send_sideband_msg(sb_adapter0_rsp_active());
  wait_fdi_state(RDI_STATE_ACTIVE, DEFAULT_WAIT_CYCLES);
endtask

task automatic bring_link_to_active();
  @(negedge reset);
  complete_active_bringup();
endtask
