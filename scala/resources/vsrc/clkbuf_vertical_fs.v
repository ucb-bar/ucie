// Behavioral model of the UCIe clock tree buffer on a vertical run, driving up: one inverter
// (INVD24BWP240H8P57PDULVT). The supply pins are connected by the physical flow.
module clkbuf_vertical_fs(
    input Vin,
    output Vout
);
    assign Vout = ~Vin;
endmodule
