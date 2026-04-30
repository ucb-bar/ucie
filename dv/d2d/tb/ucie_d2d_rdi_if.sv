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
  logic [2:0]            plSpeedmode;
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
    plSpeedmode = 3'h0;
    plMaxSpeedmode = 1'b0;
    plLnkCfg = 3'h0;
    plClkReq = 1'b0;
    plWakeAck = 1'b0;
    plCfg = '0;
    plCfgVld = 1'b0;
    plCfgCrd = 1'b1;
  endtask
endinterface
