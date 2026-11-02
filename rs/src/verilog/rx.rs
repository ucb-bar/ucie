//! The RX data lane's timing against its clock: the bench for the clock tree
//! replica in its data path.
//!
//! Run at every level. The data has to reach the deserializer as late as the
//! clock that samples it, but for the lane's own delay line, and that line has
//! to move the data and not the clock.

#[cfg(test)]
mod tests {
    use anyhow::Result;
    use test_log::test;

    use crate::verilog::{Level, tests::expect_clean};

    #[test]
    fn replica_eye() -> Result<()> {
        expect_clean(Level::Eye, "rx_replica_tb")
    }

    #[test]
    fn replica_circuit() -> Result<()> {
        expect_clean(Level::Circuit, "rx_replica_tb")
    }
}
