package ucie_d2d_dv_pkg;

`ifndef D2D_DV_DATA_BYTES
`define D2D_DV_DATA_BYTES 32
`endif

`ifndef D2D_DV_SIDEBAND_WIDTH
`define D2D_DV_SIDEBAND_WIDTH 32
`endif

  localparam int DATA_BYTES = `D2D_DV_DATA_BYTES;
  localparam int DATA_BITS = DATA_BYTES * 8;
  localparam int SIDEBAND_WIDTH = `D2D_DV_SIDEBAND_WIDTH;
  localparam int DEFAULT_TEST_TIMEOUT_CYCLES = 1000;

  localparam logic [3:0] RDI_STATE_RESET = 4'h0;
  localparam logic [3:0] RDI_STATE_ACTIVE = 4'h1;
  localparam logic [3:0] RDI_STATE_LINKERROR = 4'ha;
  localparam logic [3:0] RDI_STATE_RETRAIN = 4'hb;

  localparam logic [3:0] RDI_STATE_REQ_NOP = 4'h0;
  localparam logic [3:0] RDI_STATE_REQ_ACTIVE = 4'h1;
  localparam logic [3:0] RDI_STATE_REQ_LINKERROR = 4'ha;
  localparam logic [3:0] RDI_STATE_REQ_RETRAIN = 4'hb;

  function automatic string rdi_state_name(logic [3:0] state);
    case (state)
      RDI_STATE_RESET:     return "Reset";
      RDI_STATE_ACTIVE:    return "Active";
      RDI_STATE_LINKERROR: return "LinkError";
      RDI_STATE_RETRAIN:   return "Retrain";
      default:             return "Unknown";
    endcase
  endfunction

endpackage
