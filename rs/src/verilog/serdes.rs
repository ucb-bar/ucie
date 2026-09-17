//! Benches for the serializer and deserializer trees inside a tile.
//!
//! Both are digital and live in `verilog/common`, so they behave the same
//! whatever level is compiled next to them and run at the cheapest one only.
//! What they check is bit ORDER: the trees pair adjacent bus bits, which emits
//! and absorbs the reversal `ucie_serdes_order` describes, and getting that
//! wrong is invisible in a tile-to-tile loopback because the two trees cancel.

#[cfg(test)]
mod tests {
    use anyhow::Result;
    use test_log::test;

    use crate::verilog::{Level, harness::run};

    #[test]
    fn ser21() -> Result<()> {
        run(Level::Eye, "ser21_tb")?;
        Ok(())
    }

    #[test]
    fn tree_ser32() -> Result<()> {
        run(Level::Eye, "ser_tb")?;
        Ok(())
    }

    #[test]
    fn des12() -> Result<()> {
        run(Level::Eye, "des12_tb")?;
        Ok(())
    }

    #[test]
    fn tree_des32() -> Result<()> {
        run(Level::Eye, "des_tb")?;
        Ok(())
    }
}
