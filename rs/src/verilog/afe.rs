//! Benches for the analog front end cells every abstraction level provides.
//!
//! These come from `verilog/common/afe_tb.vams`, are written against the model
//! contract rather than any one level's implementation of it, and so run at
//! every level. A cell that passes here can be swapped for another level's
//! version of it without anything upstream noticing.

/// The cells every level has to define, with the pins `verilog/README.md`
/// lists. Everything else in a level's directory is its own business.
pub const CONTRACT_CELLS: [&str; 7] = [
    "tx_tile_driver",
    "pad_driver_cell",
    "termination",
    "rdac",
    "rx_afe",
    "dcdl",
    "clocking_distribution_model",
];

#[cfg(test)]
mod tests {
    use anyhow::Result;
    use regex::Regex;
    use test_log::test;

    use super::CONTRACT_CELLS;
    use crate::verilog::{Level, model_src_files, tests::expect_clean};

    /// A level that is missing a cell fails to elaborate against a testbench
    /// that instantiates it, which is a confusing way to find out. This says so
    /// directly, and without a simulator.
    #[test]
    fn every_level_defines_every_contract_cell() -> Result<()> {
        for level in Level::ALL {
            let sources = model_src_files(level)
                .iter()
                .map(std::fs::read_to_string)
                .collect::<std::io::Result<Vec<_>>>()?
                .join("\n");
            for cell in CONTRACT_CELLS {
                let declaration = Regex::new(&format!(r"(?m)^module\s+{cell}\b"))?;
                assert!(
                    declaration.is_match(&sources),
                    "the {} level does not define `{cell}`",
                    level.dir_name()
                );
            }
        }
        Ok(())
    }

    // Benches written against the model contract in `verilog/README.md`
    // have to hold at every level, so each runs at both. The level is in
    // the test name rather than a module around it, which keeps
    // `cargo test eye` selecting the fast half.

    #[test]
    fn rdac_eye() -> Result<()> {
        expect_clean(Level::Eye, "rdac_tb")
    }

    #[test]
    fn rdac_circuit() -> Result<()> {
        expect_clean(Level::Circuit, "rdac_tb")
    }

    #[test]
    fn pad_driver_data_eye() -> Result<()> {
        expect_clean(Level::Eye, "pad_driver_data_tb")
    }

    #[test]
    fn pad_driver_data_circuit() -> Result<()> {
        expect_clean(Level::Circuit, "pad_driver_data_tb")
    }

    #[test]
    fn pad_driver_impedance_eye() -> Result<()> {
        expect_clean(Level::Eye, "pad_driver_impedance_tb")
    }

    #[test]
    fn pad_driver_impedance_circuit() -> Result<()> {
        expect_clean(Level::Circuit, "pad_driver_impedance_tb")
    }

    #[test]
    fn tx_tile_driver_data_eye() -> Result<()> {
        expect_clean(Level::Eye, "tx_tile_driver_data_tb")
    }

    #[test]
    fn tx_tile_driver_data_circuit() -> Result<()> {
        expect_clean(Level::Circuit, "tx_tile_driver_data_tb")
    }

    #[test]
    fn tx_tile_driver_impedance_eye() -> Result<()> {
        expect_clean(Level::Eye, "tx_tile_driver_impedance_tb")
    }

    #[test]
    fn tx_tile_driver_impedance_circuit() -> Result<()> {
        expect_clean(Level::Circuit, "tx_tile_driver_impedance_tb")
    }

    #[test]
    fn rx_afe_eye() -> Result<()> {
        expect_clean(Level::Eye, "rx_afe_tb")
    }

    #[test]
    fn rx_afe_circuit() -> Result<()> {
        expect_clean(Level::Circuit, "rx_afe_tb")
    }

    #[test]
    fn termination_eye() -> Result<()> {
        expect_clean(Level::Eye, "termination_tb")
    }

    #[test]
    fn termination_circuit() -> Result<()> {
        expect_clean(Level::Circuit, "termination_tb")
    }
}
