use const_format::concatcp;

use crate::verilog::VERILOG_SRC_DIR;

pub const TX_SRC: &str = concatcp!(VERILOG_SRC_DIR, "/tx.sv");
pub const DRIVER_SRC: &str = concatcp!(VERILOG_SRC_DIR, "/driver.vams");
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
            tx::{DCDL_SRC, DRIVER_SRC, TX_SRC},
        },
    };

    const SRC_FILES: &[&str] = &[DCDL_SRC, TX_SRC, DRIVER_SRC, PRIMITIVES_SV_SRC];

    #[test]
    fn ser21() -> Result<()> {
        let work_dir = out_dir("ser21");
        simulate(SRC_FILES, "tb_ser21", &work_dir)?;
        Ok(())
    }

    #[test]
    fn tree_ser32() -> Result<()> {
        let work_dir = out_dir("tree_ser32");
        simulate(SRC_FILES, "tb_ser", &work_dir)?;
        Ok(())
    }

    #[test]
    fn driver_data() -> Result<()> {
        let work_dir = out_dir("driver_impedance");
        simulate(SRC_FILES, "tb_driver_data", &work_dir)?;
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
        simulate(SRC_FILES, "tb_driver_impedance", &work_dir)?;
        let output = read_to_string(work_dir.join("xrun.out"))?;
        assert_eq!(
            output.matches("Error").count(),
            0,
            "output should have no functionality errors"
        );
        Ok(())
    }
}
