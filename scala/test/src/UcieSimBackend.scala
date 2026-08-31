/*
  Description:
    Simulator backend selector for all ChiselSim tests, applied invisibly via
    the per-package `package object`s defined at the bottom of this file, so
    test classes need no modification.

      ./mill test                              -> Verilator (default, fast)
      UCIE_SIM_BACKEND=vcs ./mill test         -> VCS with coverage (-cm ...),
                                                  one simulation.vdb per test under
                                                  build/chiselsim/<Test>/<scenario>/workdir-vcs/

    Merge/view coverage: see verdi_coverage/run_verdi_coverage.sh.
 */

package edu.berkeley.cs.uciedigital

import chisel3.simulator.HasSimulator

object UcieSimBackend {
  def fromEnv: HasSimulator = {
    sys.env.get("UCIE_SIM_BACKEND") match {
      case Some("vcs") =>
        val cov = svsim.vcs.Backend.CoverageSettings(
          line = true,
          cond = false,
          fsm = true,
          tgl = false,
          branch = true,
          `assert` = true
        )
        HasSimulator.simulators.vcs(
          svsim.CommonCompilationSettings(),
          svsim.vcs.Backend.CompilationSettings(
            // compile-time -cm: instruments the design
            coverageSettings = cov,
            // run-time -cm: actually records coverage into the vdb testdata
            simulationSettings = svsim.vcs.Backend.SimulationSettings(
              coverageSettings = cov
            ),
            waitForLicenseIfUnavailable = true
          )
        )
      case _ => HasSimulator.default // Verilator
    }
  }
}

package object d2dadapter {
  implicit def ucieSimulator: HasSimulator = UcieSimBackend.fromEnv
}

package object logphy {
  implicit def ucieSimulator: HasSimulator = UcieSimBackend.fromEnv
}

package object phy {
  implicit def ucieSimulator: HasSimulator = UcieSimBackend.fromEnv
}

package object sideband {
  implicit def ucieSimulator: HasSimulator = UcieSimBackend.fromEnv
}

package object utils {
  implicit def ucieSimulator: HasSimulator = UcieSimBackend.fromEnv
}
