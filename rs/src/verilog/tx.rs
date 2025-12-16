use const_format::concatcp;

use crate::verilog::VERILOG_SRC_DIR;

pub const TX_SRC: &str = concatcp!(VERILOG_SRC_DIR, "/tx.sv");

#[cfg(test)]
mod tests {
    use std::fs::read_to_string;

    use anyhow::Result;
    use regex::Regex;
    use test_log::test;

    use crate::{
        tests::out_dir,
        verilog::{clocking::CLOCKING_SRC, primitives::PRIMITIVES_SV_SRC, simulate, tx::TX_SRC},
    };

    const SRC_FILES: &[&str] = &[TX_SRC, CLOCKING_SRC, PRIMITIVES_SV_SRC];

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
}
