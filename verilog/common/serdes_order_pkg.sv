`timescale 1ps/100fs

// The permutation a lane's serdes tree applies, and the shuffle that cancels
// it.
//
// A TX tile serializes through an adjacent-pairing binary tree, so the bit it
// sends in UI `t` is `DataIN[tree_bit_order(t)]` -- the reversal of the
// `SERDES_STAGES` index bits -- and an RX tile deserializes through the mirror
// image, so `dout[j]` is the bit it received in UI `tree_bit_order(j)`. The
// digital PHY cancels that with a per-lane shuffler in front of each
// serializer and behind each deserializer; a testbench that wires tiles up
// directly has to stand in for those shufflers itself, which is what
// `shuffle` is for. See `tree_ser` in `common/tx.sv` and `Phy.treeBitOrder` in
// `phy/Phy.scala`.
package ucie_serdes_order;

    // Reversal of the `SERDES_STAGES` index bits. Its own inverse, so the same
    // mapping serves in either direction.
    function automatic integer tree_bit_order(input integer t);
        tree_bit_order = 0;
        for (int b = 0; b < `SERDES_STAGES; b++) begin
            tree_bit_order |= ((t >> b) & 1) << (`SERDES_STAGES - 1 - b);
        end
    endfunction

    // What a lane's shuffler does out of reset: `dout[i] = din[bitrev(i)]`.
    // Feed it a word in the order it should appear ON THE WIRE to get the
    // `DataIN` a tile wants, and feed it a tile's `dout` to get the word back
    // in wire order.
    function automatic logic [2**`SERDES_STAGES-1:0] shuffle(
        input logic [2**`SERDES_STAGES-1:0] din
    );
        for (int i = 0; i < 2**`SERDES_STAGES; i++) begin
            shuffle[i] = din[tree_bit_order(i)];
        end
    endfunction

endpackage
