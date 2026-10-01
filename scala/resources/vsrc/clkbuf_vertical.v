// Behavioral model of the UCIe clock tree buffer on a vertical run, driving down: one inverter.
// The supply pins are connected by the physical flow.
module clkbuf_vertical(
    input Vin,
    output Vout
);
    assign Vout = ~Vin;
endmodule
