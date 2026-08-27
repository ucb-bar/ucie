use const_format::concatcp;

use crate::verilog::VERILOG_SRC_DIR;

pub const TX_SV_SRC: &str = concatcp!(VERILOG_SRC_DIR, "/tx.sv");
pub const TX_VAMS_SRC: &str = concatcp!(VERILOG_SRC_DIR, "/tx.vams");
pub const DCDL_SRC: &str = concatcp!(VERILOG_SRC_DIR, "/dcdl.vams");

#[cfg(test)]
mod tests {
    use std::fs::read_to_string;

    use anyhow::Result;
    use test_log::test;

    use crate::{
        tests::out_dir,
        verilog::{
            primitives::PRIMITIVES_SV_SRC,
            simulate,
            tx::{DCDL_SRC, TX_SV_SRC, TX_VAMS_SRC},
        },
    };

    const SRC_FILES: &[&str] = &[DCDL_SRC, TX_SV_SRC, TX_VAMS_SRC, PRIMITIVES_SV_SRC];

    #[test]
    fn ser21() -> Result<()> {
        let work_dir = out_dir("ser21");
        simulate(SRC_FILES, "ser21_tb", &work_dir)?;
        Ok(())
    }

    #[test]
    fn tree_ser32() -> Result<()> {
        let work_dir = out_dir("tree_ser32");
        simulate(SRC_FILES, "ser_tb", &work_dir)?;
        Ok(())
    }

    #[test]
    fn driver_data() -> Result<()> {
        let work_dir = out_dir("driver_data");
        simulate(SRC_FILES, "driver_data_tb", &work_dir)?;
        let output = read_to_string(work_dir.join("xrun.out"))?;
        assert_eq!(
            output.matches("Error").count(),
            0,
            "output should have no functionality errors"
        );
        Ok(())
    }

    #[test]
    fn driver_impedance() -> Result<()> {
        let work_dir = out_dir("driver_impedance");
        simulate(SRC_FILES, "driver_impedance_tb", &work_dir)?;
        let output = read_to_string(work_dir.join("xrun.out"))?;
        assert_eq!(
            output.matches("Error").count(),
            0,
            "output should have no functionality errors"
        );
        Ok(())
    }
}
