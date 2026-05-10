interface ucie_d2d_fdi_if #(
  parameter int DATA_BITS = 256,
  parameter int SIDEBAND_WIDTH = 32
);
  logic                  lclk;
  logic                  lpIrdy;
  logic                  lpValid;
  logic [DATA_BITS-1:0]  lpData;
  logic                  plTrdy;
  logic                  plValid;
  logic [DATA_BITS-1:0]  plData;
  logic [3:0]            lpStateReq;
  logic                  lpLinkError;
  logic [3:0]            plStateSts;
  logic                  plInbandPres;
  logic                  plError;
  logic                  plCError;
  logic                  plNfError;
  logic                  plTrainError;
  logic                  plPhyInRecenter;
  logic                  plRxActiveReq;
  logic                  lpRxActiveSts;
  logic [3:0]            plProtocol;
  logic [3:0]            plProtocolFlitFmt;
  logic                  plProtocolVld;
  logic                  plStallReq;
  logic                  lpStallAck;
  logic [3:0]            plSpeedmode;
  logic                  plMaxSpeedmode;
  logic [2:0]            plLnkCfg;
  logic                  plClkReq;
  logic                  lpClkAck;
  logic                  lpWakeReq;
  logic                  plWakeAck;
  logic [SIDEBAND_WIDTH-1:0] plCfg;
  logic                  plCfgVld;
  logic                  plCfgCrd;
  logic [SIDEBAND_WIDTH-1:0] lpCfg;
  logic                  lpCfgVld;
  logic                  lpCfgCrd;

  // Drive logical protocol inputs from the FDI partner to a safe idle value
  task automatic drive_idle();
    lpIrdy = 1'b0;
    lpValid = 1'b0;
    lpData = '0;
    lpStateReq = 4'h0;
    lpLinkError = 1'b0;
    lpRxActiveSts = 1'b0;
    lpStallAck = 1'b0;
    lpClkAck = 1'b0;
    lpWakeReq = 1'b0;
    lpCfg = '0;
    lpCfgVld = 1'b0;
    plCfgCrd = 1'b1;
  endtask

  task automatic request_active();
    lpStateReq = 4'h1;
  endtask

  task automatic clear_state_request();
    lpStateReq = 4'h0;
  endtask

  task automatic recv_sideband_msg(output logic [127:0] msg, input int max_cycles);
    int beat;
    int cycle;

    msg = '0;
    for (cycle = 0; cycle < max_cycles && plCfgVld !== 1'b1; cycle++) begin
      @(posedge lclk) begin
      end
    end

    if (plCfgVld !== 1'b1) begin
      $fatal(1, "Timed out waiting for FDI sideband output");
    end

    for (beat = 0; beat < (128 / SIDEBAND_WIDTH); beat++) begin
      msg[beat * SIDEBAND_WIDTH +: SIDEBAND_WIDTH] = plCfg;
      @(posedge lclk) begin
      end
    end
  endtask

endinterface
