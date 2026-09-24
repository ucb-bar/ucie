// Behavioral model of the UCIe clock tree buffer on a horizontal run: one inverter
// (INVD24BWP240H8P57PDULVT). The supply pins are connected by the physical flow.
module clkbuf_horizontal(
    input Vin,
    output Vout
);
    assign Vout = ~Vin;
endmodule
