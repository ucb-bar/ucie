package edu.berkeley.cs.uciedigital.phy.macros

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils

// Toggles `rx_clock_lane`'s gate enable at random times, with the clock
// running, parked low and parked high. No pulse may be cut short, and each
// pulse must pass or block by the enable at the third rising edge before it:
// three flops, then the clock gate cell. The model has no delays, so only the
// edges' order matters.
class RxClkLaneGateStimulus extends ExtModule {
  val clkin = IO(Output(Clock()))
  val clk_gate_en = IO(Output(Bool()))
  val clkout = IO(Input(Clock()))

  setInline(
    "RxClkLaneGateStimulus.sv",
    """
      module RxClkLaneGateStimulus(
        output reg clkin,
        output reg clk_gate_en,
        input clkout
      );
        timeunit 1ps;
        timeprecision 1fs;

        localparam real T = 125.0;
        localparam integer TOGGLES = 60;

        // Each rising edge, by index, and the enable it sampled.
        integer m = 0;
        bit en_at [int];
        integer wrong = 0, passed = 0, blocked = 0, runts = 0;
        always @(posedge clkin) begin
          automatic int n = m;
          en_at[n] = clk_gate_en;
          m++;
          #(T / 4);
          if (n >= 3) begin
            if (clkout !== en_at[n - 3]) wrong++;
            if (en_at[n - 3]) passed++; else blocked++;
          end
        end

        // No high pulse shorter than half a period. A clock parked high makes
        // a long pulse, not a short one.
        realtime rise = -1;
        always @(posedge clkout) rise = $realtime;
        always @(negedge clkout)
          if (rise >= 0 && $realtime - rise < T / 2 - 0.01) runts++;

        task automatic run(input integer periods);
          repeat (periods) begin #(T / 2) clkin = 1; #(T / 2) clkin = 0; end
        endtask

        integer k;
        initial begin
          clkin = 0; clk_gate_en = 0;
          fork
            run(4000);
            repeat (TOGGLES) begin
              // Off the clock's edges, which fall on multiples of T / 2.
              #($urandom_range(20 * 125000, 2 * 125000) / 1000.0 + 0.37);
              clk_gate_en = ~clk_gate_en;
            end
          join
          // Parked low, then parked high, with the enable moving meanwhile.
          for (k = 0; k < 2; k++) begin
            #(T / 2) clkin = k;
            #(10 * T + 0.37) clk_gate_en = ~clk_gate_en;
            #(10 * T) clkin = 0;
            run(20);
          end
          $display("%0d pulses passed, %0d blocked, %0d out of step, %0d runts",
                   passed, blocked, wrong, runts);
          if (passed == 0 || blocked == 0)
            $fatal(1, "the enable never both opened and shut the gate");
          if (wrong != 0 || runts != 0)
            $fatal(1, "%0d pulses out of step with the enable, %0d runts", wrong, runts);
          $display("TEST PASSED");
          $finish;
        end
      endmodule
    """.trim
  )
}

class RxClkLaneGateHarness extends RawModule {
  val lane = Module(new RxClkLane()(includeDefaultModels = true))
  val stim = Module(new RxClkLaneGateStimulus)
  lane.io.clkin := stim.clkin
  lane.io.clk_gate_en := stim.clk_gate_en
  stim.clkout := lane.io.clkout
  lane.io.zen := false.B
  lane.io.zctl.foreach(_ := false.B)
  lane.io.a_en := false.B
  lane.io.a_pc := false.B
  lane.io.b_en := false.B
  lane.io.b_pc := false.B
  lane.io.sel_a := false.B
  lane.io.vref_sel.foreach(_ := false.B)
}

class RxClkLaneGateSpec extends AnyFunSpec {
  describe("rx_clock_lane") {
    it(
      "should gate the RX clock three edges after its enable, with no runt, using Xcelium"
    ) {
      implicit val p = Parameters.empty
      Utils.simulate(
        new RxClkLaneGateHarness,
        Utils.writeXrunSimScript,
        Utils.buildRoot / "RxClkLaneGate_should_gate_the_RX_clock_three_edges_after_its_enable"
      )
    }
  }
}
