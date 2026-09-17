//! The whole PHY: every lane's tiles, the clock distribution between them, and
//! a loopback from each transmitter to the receiver facing it.
//!
//! Run at every level, which is what says the levels are interchangeable at
//! more than cell granularity. The circuit level is by far the longest bench
//! here -- the switched-capacitor front ends put the analog solver on
//! femtosecond steps, twenty lanes over -- so reach for the eye level while
//! iterating.

#[cfg(test)]
mod tests {
    use anyhow::Result;
    use test_log::test;

    use crate::verilog::{Level, harness::expect_clean};

    #[test]
    fn eye() -> Result<()> {
        expect_clean(Level::Eye, "phy_tb")
    }

    #[test]
    fn circuit() -> Result<()> {
        expect_clean(Level::Circuit, "phy_tb")
    }
}
