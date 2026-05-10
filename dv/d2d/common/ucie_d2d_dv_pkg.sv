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
  localparam int DEFAULT_WAIT_CYCLES = 200;

  localparam logic [3:0] RDI_STATE_RESET = 4'h0;
  localparam logic [3:0] RDI_STATE_ACTIVE = 4'h1;
  localparam logic [3:0] RDI_STATE_LINKRESET = 4'h9;
  localparam logic [3:0] RDI_STATE_LINKERROR = 4'ha;
  localparam logic [3:0] RDI_STATE_RETRAIN = 4'hb;

  localparam logic [3:0] RDI_STATE_REQ_NOP = 4'h0;
  localparam logic [3:0] RDI_STATE_REQ_ACTIVE = 4'h1;
  localparam logic [3:0] RDI_STATE_REQ_LINKRESET = 4'h9;
  localparam logic [3:0] RDI_STATE_REQ_RETRAIN = 4'hb;

  localparam logic [4:0] SB_OP_MSG_WITHOUT_DATA = 5'b10010;
  localparam logic [4:0] SB_OP_MSG_WITH_64B_DATA = 5'b11011;

  localparam logic [7:0] SB_ADAPTER0_REQ_ACTIVE_MSGCODE = 8'h03;
  localparam logic [7:0] SB_ADAPTER0_RSP_ACTIVE_MSGCODE = 8'h04;
  localparam logic [7:0] SB_ADAPTER0_RSP_LINKRESET_MSGCODE = 8'h04;
  localparam logic [7:0] SB_ADVCAP_ADAPTER_MSGCODE = 8'h01;

  localparam logic [7:0] SB_ACTIVE_SUBCODE = 8'h01;
  localparam logic [7:0] SB_LINKRESET_SUBCODE = 8'h09;
  localparam logic [7:0] SB_ADVCAP_ADAPTER_SUBCODE = 8'h00;

  localparam logic [63:0] SB_ADVCAP_RAW_STREAMING_STACK0 = 64'h0000_0000_0000_0091;

  function automatic string rdi_state_name(logic [3:0] state);
    case (state)
      RDI_STATE_RESET:     return "Reset";
      RDI_STATE_ACTIVE:    return "Active";
      RDI_STATE_LINKRESET: return "LinkReset";
      RDI_STATE_LINKERROR: return "LinkError";
      RDI_STATE_RETRAIN:   return "Retrain";
      default:             return "Unknown";
    endcase
  endfunction

  function automatic logic [63:0] sb_header(
    input logic [4:0] opcode,
    input logic [7:0] msgcode,
    input logic [7:0] msgsubcode,
    input logic [63:0] data
  );
    logic [63:0] header;

    header = '0;
    header[4:0] = opcode;
    header[21:14] = msgcode;
    header[25:24] = 2'b01; // Route to the local D2D layer in the current switch.
    header[31:29] = 3'b001; // D2D source.
    header[39:32] = msgsubcode;
    header[58:56] = 3'b101; // Remote D2D destination.
    header[62] = ^header[61:0];
    header[63] = (opcode == SB_OP_MSG_WITH_64B_DATA) ? ^data : 1'b0;
    return header;
  endfunction

  function automatic logic [127:0] sb_msg(
    input logic [4:0] opcode,
    input logic [7:0] msgcode,
    input logic [7:0] msgsubcode,
    input logic [63:0] data
  );
    return {data, sb_header(opcode, msgcode, msgsubcode, data)};
  endfunction

  function automatic bit sb_msg_matches(
    input logic [127:0] msg,
    input logic [4:0] opcode,
    input logic [7:0] msgcode,
    input logic [7:0] msgsubcode
  );
    return msg[4:0] == opcode && msg[21:14] == msgcode && msg[39:32] == msgsubcode;
  endfunction

  function automatic logic [127:0] sb_advcap_adapter();
    return sb_msg(
      SB_OP_MSG_WITH_64B_DATA,
      SB_ADVCAP_ADAPTER_MSGCODE,
      SB_ADVCAP_ADAPTER_SUBCODE,
      SB_ADVCAP_RAW_STREAMING_STACK0
    );
  endfunction

  function automatic logic [127:0] sb_adapter0_req_active();
    return sb_msg(
      SB_OP_MSG_WITHOUT_DATA,
      SB_ADAPTER0_REQ_ACTIVE_MSGCODE,
      SB_ACTIVE_SUBCODE,
      64'h0
    );
  endfunction

  function automatic logic [127:0] sb_adapter0_rsp_active();
    return sb_msg(
      SB_OP_MSG_WITHOUT_DATA,
      SB_ADAPTER0_RSP_ACTIVE_MSGCODE,
      SB_ACTIVE_SUBCODE,
      64'h0
    );
  endfunction

  function automatic logic [127:0] sb_adapter0_rsp_linkreset();
    return sb_msg(
      SB_OP_MSG_WITHOUT_DATA,
      SB_ADAPTER0_RSP_LINKRESET_MSGCODE,
      SB_LINKRESET_SUBCODE,
      64'h0
    );
  endfunction

  function automatic bit sb_is_advcap_adapter(input logic [127:0] msg);
    return sb_msg_matches(
      msg,
      SB_OP_MSG_WITH_64B_DATA,
      SB_ADVCAP_ADAPTER_MSGCODE,
      SB_ADVCAP_ADAPTER_SUBCODE
    );
  endfunction

  function automatic bit sb_is_adapter0_rsp_active(input logic [127:0] msg);
    return sb_msg_matches(
      msg,
      SB_OP_MSG_WITHOUT_DATA,
      SB_ADAPTER0_RSP_ACTIVE_MSGCODE,
      SB_ACTIVE_SUBCODE
    );
  endfunction

endpackage
