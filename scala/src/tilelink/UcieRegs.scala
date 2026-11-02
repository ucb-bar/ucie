package edu.berkeley.cs.uciedigital.tilelink

import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import testchipip.soc.{CanHaveChipletRouting, ChipletRoutingKey}

// Each UCIe link's register port, on the chiplet router's control bus. The
// router cannot attach these itself: each is a crossbar in front of UcieTL's
// register blocks, not the bare register node it takes. The port is
// synchronous to the link's clock, so the control bus must run at the same
// frequency (the router already requires that of its buses).
trait CanHaveUcieRegs { this: BaseSubsystem with CanHaveChipletRouting =>
  d2d_ports.foreach { ports =>
    val cbus = locateTLBusWrapper(p(ChipletRoutingKey).get.controlBusWhere)
    ports.collect { case link: UcieChipletLink => link }.foreach { link =>
      cbus.coupleTo(s"${link.name}_control") {
        link.regNode := TLWidthWidget(cbus.beatBytes) := TLBuffer() :=
          TLFragmenter(cbus) := _
      }
    }
  }
}
