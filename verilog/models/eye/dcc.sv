`timescale 1ps/100fs

module dcc #(
    parameter real T_ON = 20000.0   // turn-on time (ps)
)(
    input  logic clk_in,
    output logic clk_out
);

    logic enabled = 1'b0;
    real  half    = 0.0;   // measured half period (ps)
    real  prev    = 0.0;   // time of the previous input rising edge

    initial begin
        clk_out = 1'b0;
        #(T_ON) enabled = 1'b1;
    end

    always @(posedge clk_in) begin
        if (prev > 0.0) half = ($realtime - prev) / 2.0;
        prev = $realtime;
    end

    task automatic pulse(real hi);
        clk_out = 1'b1;
        #(hi);
        clk_out = 1'b0;
    endtask

    always @(posedge clk_in) begin
        if (enabled && half > 0.0) fork pulse(half); join_none
    end

endmodule
