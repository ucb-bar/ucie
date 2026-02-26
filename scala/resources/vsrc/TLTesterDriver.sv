`define UCIE_PHY_Q1_BASE 64'h20000
`define TEST_TARGET 64'h0
`define TX_TEST_MODE 64'h8
`define TX_DATA_MODE 64'h10
`define TX_LFSR_SEED 64'h18
`define TX_FSM_RST 64'hA0
`define TX_EXECUTE 64'hA8
`define TX_WRITE_CHUNK 64'hB0
`define TX_PACKETS_SENT 64'hB8
`define TX_MANUAL_REPEAT_PERIOD 64'hC0
`define TX_PACKETS_TO_SEND 64'hC8
`define TX_CLK_P 64'hD0
`define TX_CLK_N 64'hD8
`define TX_TRACK 64'hE0
`define TX_DATA_LANE_GROUP 64'hE8
`define TX_DATA_OFFSET 64'hF0
`define TX_DATA_CHUNK_IN_0 64'hF8
`define TX_DATA_CHUNK_IN_1 64'h100
`define TX_DATA_CHUNK_OUT_0 64'h108
`define TX_DATA_CHUNK_OUT_1 64'h110
`define TX_TEST_STATE 64'h118
`define RX_DATA_MODE 64'h120
`define RX_LFSR_SEED 64'h128
`define RX_BIT_ERRORS 64'h1B0
`define RX_FSM_RST 64'h240
`define RX_PACKETS_TO_RECEIVE 64'h248
`define RX_PAUSE_COUNTERS 64'h250
`define RX_PACKETS_RECEIVED 64'h258
`define RX_SIGNATURE 64'h260
`define RX_DATA_LANE 64'h268
`define RX_DATA_OFFSET 64'h270
`define RX_DATA_CHUNK 64'h278
`define PLL_DREF_LOW 64'h280
`define PLL_DREF_HIGH 64'h288
`define PLL_DCOARSE 64'h290
`define PLL_D_KP 64'h298
`define PLL_D_KI 64'h2A0
`define PLL_D_CLOL 64'h2A8
`define PLL_D_OL_FCW 64'h2B0
`define PLL_D_ACCUMULATOR_RESET 64'h2B8
`define PLL_VCO_RESET 64'h2C0
`define PLL_DIGITAL_RESET 64'h2C8
`define TEST_PLL_DREF_LOW 64'h2D0
`define TEST_PLL_DREF_HIGH 64'h2D8
`define TEST_PLL_DCOARSE 64'h2E0
`define TEST_PLL_D_KP 64'h2E8
`define TEST_PLL_D_KI 64'h2F0
`define TEST_PLL_D_CLOL 64'h2F8
`define TEST_PLL_D_OL_CFW 64'h300
`define TEST_PLL_D_ACCUMULATOR_RESET 64'h308
`define TEST_PLL_VCO_RESET 64'h310
`define TEST_PLL_DIGITAL_RESET 64'h318
`define PLL_OUTPUT 64'h320
`define TEST_PLL_OUTPUT 64'h328
`define PLL_BYPASS_EN 64'h330
`define TX_CTL 64'h338
`define TX_CTL_WIDTH 64'h130
`define DLL_RESET_OFS 64'h0
`define DRIVER_OFS 64'h8
`define SKEW_OFS 64'h10
`define SHUFFLER_OFS 64'h18
`define TX_SAMPLE_NEGEDGE_OFS 64'h118
`define TX_DELAY_OFS 64'h120
`define DLL_CODE_OFS 64'h128
`define RX_CTL 64'h1C28
`define RX_CTL_WIDTH 64'h48
`define ZEN_OFS 64'h0
`define ZCTL_OFS 64'h8
`define VREF_SEL_OFS 64'h10
`define AFE_BYPASS_EN_OFS 64'h18
`
`define AFE_BYPASS_OFS 64'h20
`define AFE_OP_CYCLES_OFS 64'h28
`define AFE_OVERLAP_CYCLES_OFS 64'h30
`define RX_SAMPLE_NEGEDGE_OFS 64'h38
`define RX_DELAY_OFS 64'h40
`define COMMON_TX_TEST_MODE 64'h2210
`define COMMON_TX_DATA_MODE 64'h2218
`define COMMON_TX_LFSR_SEED 64'h2220
`define COMMON_TX_FSM_RST 64'h2228
`define COMMON_TX_EXECUTE 64'h2230
`define COMMON_TX_PACKETS_SENT 64'h2238
`define COMMON_TX_MANUAL_REPEAT_PERIOD 64'h2240
`define COMMON_TX_PACKETS_TO_SEND 64'h2248
`define COMMON_TX_TEST_STATE 64'h2250
`define COMMON_DATA 64'h2258
`define COMMON_TX_DRIVERCTL 64'h22D8
`define COMMON_TX_DLL_RESET 64'h2308
`define COMMON_TX_TXCTL_DRIVER 64'h2310
`define COMMON_TX_TXCTL_SKEW 64'h2318
`define COMMON_TX_TXCTL_SHUFFLER 64'h2320
`define COMMON_TX_DLL_CODE 64'h2420
`define TX_VALID 64'h2428
`define RX_LFSR_VALID 64'h2430
`define UCIE_STACK 64'h2430
`define OUTPUT_VALID 64'h2438
`define ERROR_COUNTS 64'h2440
`define PATTERN 64'h24C0
`define PATTERN_UI_COUNT 64'h24C8
`define TRIGGER_NEW 64'h24D0
`define TRIGGER_EXIT 64'h24D8
`define TEST_TARGET_MAINBAND 0 
`define TEST_TARGET_LOOPBACK 1
`define DATA_MODE_FINITE 0 
`define DATA_MODE_INFINITE 1
`define TX_TEST_MODE_MANUAL 0
`define TX_TEST_MODE_LFSR 1
`define TX_TEST_STATE_IDLE 0
`define TX_TEST_STATE_RUN 1
`define TX_TEST_STATE_DONE 2
`define DEFAULT_CLK_P 64'h55555555
`define DEFAULT_CLK_N 64'haaaaaaaa
`define DEFAULT_VALID 64'h0f0f0f0f
`define DEFAULT_TRACK 64'h55555555

module TLTesterDriver(
    input reg clock,
    output reg [63:0] tlt_req_bits_addr,
    output reg [63:0] tlt_req_bits_data,
    output reg tlt_req_bits_is_write,
    output reg tlt_req_valid,
    input tlt_req_ready,
    input [63:0] tlt_resp_bits_data,
    input tlt_resp_valid,
    output reg tlt_resp_ready
    );
    task op(input [63:0] addr, input [63:0] data, input is_write);
        begin
            tlt_resp_ready = 1'b1;
            tlt_req_valid = 1'b1;
            tlt_req_bits_addr = addr;
            tlt_req_bits_data = data;
            tlt_req_bits_is_write = is_write;
            fork
                if (!tlt_req_ready) @(posedge tlt_req_ready);
                repeat(1000) @(posedge clock);
            join_any
            assert(tlt_req_ready) else $error("TileLink error: timeout waiting for request to be ready");
            fork
                @(negedge clock) tlt_req_valid = 1'b0;
                fork
                    if (!tlt_resp_valid) @(posedge tlt_resp_valid);
                    repeat(1000) @(posedge clock);
                join_any
            join
            assert(tlt_resp_valid) else $error("TileLink error: timeout waiting for response to be valid");
            @(negedge clock);
        end
    endtask
    task write(input [63:0] addr, input [63:0] data);
    begin
        op(addr, data, 1'b1);
        @(negedge clock);
    end
    endtask
    task read(input [63:0] addr, output [63:0] result);
        begin
            op(addr, 64'b0, 1'b0);
            result = tlt_resp_bits_data;
            @(negedge clock);
        end
    endtask
    task expect_data(input [63:0] addr, input [63:0] data);
        begin
            reg [63:0] result;
            read(addr, result);
            assert(result === data) else $error("Assertion error: expected %64'h, got %0x", data, result);
        end
    endtask
    task reset_fsms();
        begin
            reg [63:0] result;
            read(addr, result);
            assert(result === data) else $error("Assertion error: expected %64'h, got %0x", data, result);
        end
    endtask
endmodule
