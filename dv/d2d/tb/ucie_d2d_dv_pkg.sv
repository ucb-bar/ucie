package ucie_d2d_dv_pkg;
  localparam int DATA_BYTES = 32;
  localparam int DATA_BITS = DATA_BYTES * 8;
  localparam int SIDEBAND_WIDTH = 32;

  localparam logic [3:0] RDI_STATE_RESET = 4'h0;
  localparam logic [3:0] RDI_STATE_ACTIVE = 4'h1;
  localparam logic [3:0] RDI_STATE_LINKERROR = 4'ha;
  localparam logic [3:0] RDI_STATE_RETRAIN = 4'hb;

  localparam logic [3:0] RDI_STATE_REQ_NOP = 4'h0;
  localparam logic [3:0] RDI_STATE_REQ_ACTIVE = 4'h1;
endpackage
