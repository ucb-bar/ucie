// Behavioral model of the UCIe clock tree buffer on a horizontal run: one inverter.
// The supply pins are connected by the physical flow.
module clkbuf_horizontal(
    input Vin,
    output Vout
);
    assign Vout = ~Vin;
endmodule
