package edu.berkeley.cs.uciedigital.phy.macros.clocking

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.funspec.AnyFunSpec

import edu.berkeley.cs.uciedigital.Utils

// Sweeps `global_delayline_gate` across a period of lag between TxClk and
// TxClkQ, with and without the delay line, and toggles `enable` at random
// times. With either `edgeSel`, no pulse may be cut short. Each TxClkQ pulse
// must pass or block with the TxClk pulse before it, or with the one after it
// when `edgeSel` is 1 and the lag is over half a period.
//
// The model has no delays, so the race is checked directly. With `edgeSel` set
// as software sets it (1 within a quarter period of a lag of 0), the tap must
// not change within `WINDOW` of a TxClkQ rise. The other setting must,
// somewhere in the sweep, or the check is not working.
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

        // 8 GHz. With no delays, only the lag as a fraction of the period
        // matters.
        localparam real T = 125.0;
        localparam integer LAGS = 40;
        localparam integer TOGGLES = 40;
        localparam real TAP_PS = 1.086;   // the model's tap

        // Line settings: none, and enough taps to move the lag a third of a period.
        localparam int SWEEP_TAPS[2] = '{0, 37};
        // Stands in for the TxClkQ flops' setup plus hold. Anything under a
        // quarter period passes with software's tap.
        localparam real WINDOW = 10.0;
        real line_delay = 0.0;
        bit running = 0;
        integer wrong, runts, passed, blocked, near;
        integer total_wrong = 0, total_runts = 0, total_near = 0, other_near = 0;

        // Each TxClk pulse, by index, and whether it got through.
        int dn = 0;
        bit dpass [int];
        always @(posedge VinData) if (running) begin
          automatic int n = dn;
          dn++;
          #(T / 4) dpass[n] = VoutData;
        end

        // Each TxClkQ pulse at the gate: the TxClk pulse before it, and whether
        // it got through.
        int qn = 0;
        bit qpass [int];
        int qafter [int];
        always @(posedge GlobalDelaylineGateHarness.gate.q_delayed) if (running) begin
          automatic int n = qn;
          qn++;
          qafter[n] = dn - 1;
          #(T / 4) qpass[n] = Vout;
        end

        // No pulse may be cut short. Only falls after a rise count, so the
        // edge from X at time zero is ignored.
        realtime data_rise = -1, q_rise = -1;
        always @(posedge VoutData) data_rise = $realtime;
        always @(negedge VoutData)
          if (data_rise >= 0 && $realtime - data_rise < T / 2 - 0.01) runts++;
        always @(posedge Vout) q_rise = $realtime;
        always @(negedge Vout)
          if (q_rise >= 0 && $realtime - q_rise < T / 2 - 0.01) runts++;

        // The race: the tap changing within `WINDOW` of a TxClkQ rise, on
        // either side.
        realtime tap_change = -1, q_sample = -1;
        always @(GlobalDelaylineGateHarness.gate.en_tap) begin
          tap_change = $realtime;
          if (running && q_sample >= 0 && tap_change - q_sample < WINDOW) near++;
        end
        always @(posedge GlobalDelaylineGateHarness.gate.q_delayed) begin
          q_sample = $realtime;
          if (running && tap_change >= 0 && q_sample - tap_change < WINDOW) near++;
        end

        real lag, phase;
        bit rule_sel;
        integer t, k, s, j, shift;
        initial begin
          Dctrl = '0; Vin = 0; VinData = 0; enable = 0; edgeSel = 0;
          for (t = 0; t < 2; t = t + 1) begin
            Dctrl = (64'b1 << SWEEP_TAPS[t]) - 1;
            line_delay = SWEEP_TAPS[t] * TAP_PS;
            for (k = 0; k < LAGS; k = k + 1) begin
              lag = (k + 0.5) * T / LAGS;
              // Total lag of the delayed TxClkQ, within a period.
              phase = lag + line_delay;
              while (phase >= T) phase = phase - T;
              rule_sel = phase < T / 4 || phase >= 3 * T / 4;
              for (s = 0; s < 2; s = s + 1) begin
                // Changed with the gate shut and settled, so it has no effect.
                edgeSel = (s == 0) ? rule_sel : !rule_sel;
                wrong = 0; runts = 0; passed = 0; blocked = 0; near = 0;
                dn = 0; qn = 0; dpass.delete(); qpass.delete(); qafter.delete();
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
                #(2 * T + line_delay);
                // Above half a period, the latch's tap follows the TxClk pulse
                // after.
                shift = (edgeSel && phase > T / 2) ? 1 : 0;
                for (j = 0; j < qn; j = j + 1)
                  if (qafter[j] + shift >= 1 && qafter[j] + shift < dn - 1) begin
                    if (qpass[j]) passed++; else blocked++;
                    if (qpass[j] !== dpass[qafter[j] + shift]) wrong++;
                  end
                $display("%2d taps, lag %6.2f ps, edgeSel %0d (%s): %0d of %0d quadrature pulses wrong, %0d runts, %0d close calls",
                         SWEEP_TAPS[t], lag, edgeSel, (s == 0) ? "software's" : "the other",
                         wrong, passed + blocked, runts, near);
                if (passed == 0 || blocked == 0)
                  $fatal(1, "the enable never both opened and shut the gate");
                total_wrong += wrong;
                total_runts += runts;
                if (s == 0) total_near += near;
                else other_near += near;
              end
            end
          end
          if (total_wrong != 0 || total_runts != 0)
            $fatal(1, "%0d quadrature pulses out of step with the data clock, %0d runts",
                   total_wrong, total_runts);
          if (total_near != 0)
            $fatal(1, "%0d samples of the tap within %0.1f ps of it changing",
                   total_near, WINDOW);
          if (other_near == 0)
            $fatal(1, "the other tap never came near the quadrature flops' sampling");
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
