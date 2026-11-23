package edu.berkeley.cs.uciedigital.phy.macros.clocking

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils

// Drives `global_delayline_gate` with the two TX clocks at every lag across a
// period, and with and without the delay line, toggling the enable at random
// times that bear no relation to either clock, as the chip clock's register
// block does. Each quadrature pulse has to pass exactly when the data pulse
// before it did, and neither clock may come out with a pulse cut short.
//
// Each lag runs twice: with `edgeSel` as software sets it, high for a lag
// under a quarter period, and the other way. The model has no delays, so the
// race each edge runs against the quadrature clock's latch is checked on its
// own: that latch must not close within `WINDOW` after its input changes. Set
// as software sets it, nothing may come that close. The other way has to,
// somewhere in the sweep, or the check is seeing nothing. Only the setting
// software uses has to keep the pulses in step.
//
// Without the latch that holds the data clock's decision back until its pulse
// begins, the quadrature clock goes a pulse early at every lag past half a
// period, which this catches.
class GlobalDelaylineGateLagSweep extends ExtModule {
  val Dctrl = IO(Output(UInt(ClockingTile.phaseSelWidth.W)))
  val Vin = IO(Output(Clock()))
  val VinData = IO(Output(Clock()))
  val enable = IO(Output(Bool()))
  val edgeSel = IO(Output(Bool()))
  val Vout = IO(Input(Clock()))
  val VoutData = IO(Input(Clock()))

  setInline(
    "GlobalDelaylineGateLagSweep.sv",
    """
      module GlobalDelaylineGateLagSweep(
        output reg [63:0] Dctrl,
        output reg Vin,
        output reg VinData,
        output reg enable,
        output reg edgeSel,
        input Vout,
        input VoutData
      );
        timeunit 1ps;
        timeprecision 1fs;

        // 8 GHz. The model has no delays, so what matters is the lag as a share
        // of the period, which the sweep covers whole, and the line's taps.
        localparam real T = 125.0;
        localparam integer LAGS = 40;
        localparam integer TOGGLES = 40;
        localparam real TAP_PS = 1.086;   // the model's tap

        // Line settings: none, and enough taps to move the lag a third of a period.
        localparam int SWEEP_TAPS[2] = '{0, 37};
        // Stands in for the retiming latch's clock-to-output delay plus the
        // quadrature clock latch's setup until the cell has numbers. Anything
        // under a quarter period passes with the edge software picks.
        localparam real WINDOW = 10.0;
        real line_delay = 0.0;
        bit running = 0;
        integer wrong, runts, passed, blocked, near;
        integer total_wrong = 0, total_runts = 0, total_near = 0, other_near = 0;

        // Whether the last data pulse got through.
        reg data_passed = 1'b0;
        always @(posedge VinData) #0.1 data_passed = VoutData;

        // Each quadrature pulse, out of the line, has to do what the data pulse
        // before it did.
        always @(posedge Vin) if (running) fork
          begin
            #(line_delay + 0.1);
            if (Vout) passed++; else blocked++;
            if (Vout !== data_passed) wrong++;
          end
        join_none

        // Neither clock may come out with a pulse cut short.
        // Each falls only after rising, so the edge from X at time zero is not one.
        realtime data_rise = -1, q_rise = -1;
        always @(posedge VoutData) data_rise = $realtime;
        always @(negedge VoutData)
          if (data_rise >= 0 && $realtime - data_rise < T / 2 - 0.01) runts++;
        always @(posedge Vout) q_rise = $realtime;
        always @(negedge Vout)
          if (q_rise >= 0 && $realtime - q_rise < T / 2 - 0.01) runts++;

        // The quadrature clock's latch closes as the delayed quadrature clock
        // rises. Closing within `WINDOW` after its input changes is the race;
        // just before is safe, since the retiming latch's clock-to-output
        // delay covers the hold.
        realtime q_latch_in = -1;
        always @(GlobalDelaylineGateHarness.gate.en_retimed) q_latch_in = $realtime;
        always @(posedge GlobalDelaylineGateHarness.gate.q_delayed)
          if (running && q_latch_in >= 0 && $realtime - q_latch_in < WINDOW) near++;

        real lag, phase;
        bit rule_sel;
        integer t, k, s;
        initial begin
          Dctrl = '0; Vin = 0; VinData = 0; enable = 0; edgeSel = 0;
          for (t = 0; t < 2; t = t + 1) begin
            Dctrl = (64'b1 << SWEEP_TAPS[t]) - 1;
            line_delay = SWEEP_TAPS[t] * TAP_PS;
            for (k = 0; k < LAGS; k = k + 1) begin
              lag = (k + 0.5) * T / LAGS;
              // The whole lag of the delayed quadrature clock, within a period.
              phase = lag + line_delay;
              while (phase >= T) phase = phase - T;
              rule_sel = phase < T / 4;
              for (s = 0; s < 2; s = s + 1) begin
                // Changed with the gate shut and settled, which moves nothing.
                edgeSel = (s == 0) ? rule_sel : !rule_sel;
                wrong = 0; runts = 0; passed = 0; blocked = 0; near = 0;
                running = 1;
                fork
                  while (running) begin #(T / 2) VinData = 1; #(T / 2) VinData = 0; end
                  begin
                    #(lag);
                    while (running) begin #(T / 2) Vin = 1; #(T / 2) Vin = 0; end
                  end
                  begin
                    repeat (TOGGLES) begin
                      #($urandom_range(40 * 125000, 4 * 125000) / 1000.0);
                      enable = ~enable;
                    end
                    #(8 * T);
                    running = 0;
                  end
                join
                #(T + line_delay);
                $display("%2d taps, lag %6.2f ps, edgeSel %0d (%s): %0d of %0d quadrature pulses wrong, %0d runts, %0d close calls",
                         SWEEP_TAPS[t], lag, edgeSel, (s == 0) ? "software's" : "the other",
                         wrong, passed + blocked, runts, near);
                if (passed == 0 || blocked == 0)
                  $fatal(1, "the enable never both opened and shut the gate");
                if (s == 0) begin
                  total_wrong += wrong;
                  total_runts += runts;
                  total_near += near;
                end else begin
                  other_near += near;
                end
              end
            end
          end
          if (total_wrong != 0 || total_runts != 0)
            $fatal(1, "%0d quadrature pulses out of step with the data clock, %0d runts",
                   total_wrong, total_runts);
          if (total_near != 0)
            $fatal(1, "%0d quadrature latch closings within %0.1f ps of its input changing",
                   total_near, WINDOW);
          if (other_near == 0)
            $fatal(1, "the wrong edge never came near the quadrature latch's closing");
          $display("TEST PASSED");
          $finish;
        end

      endmodule
    """.trim
  )
}

class GlobalDelaylineGateHarness extends RawModule {
  val gate = Module(new GlobalDelaylineGate()(includeDefaultModels = true))
  val sweep = Module(new GlobalDelaylineGateLagSweep)
  gate.io.Dctrl := sweep.Dctrl
  gate.io.Vin := sweep.Vin
  gate.io.VinData := sweep.VinData
  gate.io.enable := sweep.enable
  gate.io.edgeSel := sweep.edgeSel
  sweep.Vout := gate.io.Vout
  sweep.VoutData := gate.io.VoutData
}

class GlobalDelaylineGateSpec extends AnyFunSpec {
  describe("global_delayline_gate") {
    it(
      "should gate the quadrature clock right after the data clock at every lag using Xcelium"
    ) {
      implicit val p = Parameters.empty
      Utils.simulate(
        new GlobalDelaylineGateHarness,
        Utils.writeXrunSimScript,
        Utils.buildRoot / "GlobalDelaylineGate_should_gate_the_quadrature_clock_after_the_data_clock"
      )
    }
  }
}
