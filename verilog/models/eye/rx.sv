`timescale 1ps/100fs

// Digital half of the eye-level RX front end. `models/eye/rx.vams` has the
// slicer, which is analog; this is the sampler handover around it, which is
// not, and a Verilog-AMS module is one or the other.

// The half of the front end that the AFE controller sequences: each of the two
// samplers follows the slicer while it is enabled and not precharging, and
// holds its last decision otherwise, and `sel_a` picks which one drives the
// lane.
module rx_afe_holdsel (
    input logic slice,
    input logic a_en, a_pc, b_en, b_pc, sel_a,
    output logic dout
);
    logic a_val;
    logic b_val;

    // Both samplers come up holding zero rather than whatever the slicer last
    // said, so a lane that is never handed a live AFE sequence reads as quiet
    // instead of as X. Written from `initial` rather than as a declaration
    // initializer, which would count as a second driver on the variable.
    initial begin
        a_val = 1'b0;
        b_val = 1'b0;
    end

    always @(*) begin
        if (a_en && !a_pc) a_val <= #(`T_CLKQ_DQ_DEFAULT) slice;
    end
    always @(*) begin
        if (b_en && !b_pc) b_val <= #(`T_CLKQ_DQ_DEFAULT) slice;
    end

    assign #(`MUX_DELAY_DEFAULT) dout = sel_a ? a_val : b_val;
endmodule
