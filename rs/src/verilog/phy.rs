use const_format::concatcp;

use crate::verilog::VERILOG_SRC_DIR;

pub const PHY_SRC: &str = concatcp!(VERILOG_SRC_DIR, "/phy.sv");

#[cfg(test)]
mod tests {
    use anyhow::Result;
    use test_log::test;

    use crate::{
        tests::out_dir,
        verilog::{get_src_files, simulate},
    };

    #[test]
    fn phy() -> Result<()> {
        let work_dir = out_dir("phy");
        simulate(get_src_files(), "tb_phy", &work_dir)?;
        Ok(())
    }
}
