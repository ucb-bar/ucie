// Behavioral model of `mux16to1`, the analog clocking IP's 16:1 clock mux: its
// netlist's tree of fifteen 2:1 muxes, with instance and net names kept and
// supply pins omitted. `sel` is Gray coded -- `i ^ (i >> 1)` picks `Q[i]` --
// and the output is not inverted.
module mux16to1 (
  input [15:0] Q,
  input [3:0] sel,
  output clk_divided
);
  // Each 2:1 mux: Z = S ? I1 : I0.
  wire net2  = sel[0] ? Q[1]  : Q[0];   // XI41
  wire net5  = sel[0] ? Q[2]  : Q[3];   // XI42
  wire net3  = sel[0] ? Q[5]  : Q[4];   // XI44
  wire net4  = sel[0] ? Q[6]  : Q[7];   // XI43
  wire net62 = sel[0] ? Q[9]  : Q[8];   // XI47
  wire net61 = sel[0] ? Q[10] : Q[11];  // XI48
  wire net69 = sel[0] ? Q[13] : Q[12];  // XI46
  wire net70 = sel[0] ? Q[14] : Q[15];  // XI45

  wire net65 = sel[1] ? net5  : net2;   // XI49
  wire net68 = sel[1] ? net3  : net4;   // XI50
  wire net71 = sel[1] ? net69 : net70;  // XI51
  wire net72 = sel[1] ? net61 : net62;  // XI52

  wire net1  = sel[2] ? net68 : net65;  // XI53
  wire net10 = sel[2] ? net72 : net71;  // XI54

  assign clk_divided = sel[3] ? net10 : net1;  // XI55
endmodule
