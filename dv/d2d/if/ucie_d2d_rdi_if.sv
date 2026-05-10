interface ucie_d2d_rdi_if #(
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

  // Drive logical protocol inputs from the RDI/LogPhy partner to idle
  task automatic drive_idle();
    plTrdy = 1'b1;
    plValid = 1'b0;
    plData = '0;
    plStateSts = 4'h0;
    plInbandPres = 1'b0;
    plError = 1'b0;
    plCError = 1'b0;
    plNfError = 1'b0;
    plTrainError = 1'b0;
    plPhyInRecenter = 1'b0;
    plStallReq = 1'b0;
    plSpeedmode = 4'h0;
    plMaxSpeedmode = 1'b0;
    plLnkCfg = 3'h0;
    plClkReq = 1'b0;
    plWakeAck = 1'b0;
    plCfg = '0;
    plCfgVld = 1'b0;
    plCfgCrd = 1'b1;
  endtask

  task automatic drive_inband_present();
    plInbandPres = 1'b1;
  endtask

  task automatic drive_state(logic [3:0] state);
    plStateSts = state;
  endtask

  // Sideband packets are sent least-significant beat first by the Chisel serdes.
  task automatic send_sideband_msg(input logic [127:0] msg);
    int beat;
    plCfgVld = 1'b1;
    for (beat = 0; beat < (128 / SIDEBAND_WIDTH); beat++) begin
      plCfg = msg[beat * SIDEBAND_WIDTH +: SIDEBAND_WIDTH];
      @(posedge lclk);
    end
    plCfgVld = 1'b0;
    plCfg = '0;
  endtask

endinterface
