use const_format::concatcp;

use crate::verilog::VERILOG_SRC_DIR;

pub const RX_SV_SRC: &str = concatcp!(VERILOG_SRC_DIR, "/rx.sv");
pub const RX_VAMS_SRC: &str = concatcp!(VERILOG_SRC_DIR, "/rx.vams");

#[cfg(test)]
mod tests {
    use std::fs::read_to_string;

    use anyhow::Result;
    use test_log::test;

    use crate::{
        tests::out_dir,
        verilog::{
            primitives::{PRIMITIVES_SV_SRC, PRIMITIVES_VAMS_SRC},
            rx::{RX_SV_SRC, RX_VAMS_SRC},
            simulate,
        },
    };

    const SRC_FILES: &[&str] = &[
        RX_SV_SRC,
        RX_VAMS_SRC,
        PRIMITIVES_SV_SRC,
        PRIMITIVES_VAMS_SRC,
    ];

    #[test]
    fn rx_afe() -> Result<()> {
        let work_dir = out_dir("rx_afe");
        simulate(SRC_FILES, "tb_rx_afe", &work_dir)?;
        let output = read_to_string(work_dir.join("xrun.out"))?;
        assert_eq!(
            output.matches("Error").count(),
            0,
            "output should have no functionality errors"
        );
        Ok(())
    }

    #[test]
    fn termination() -> Result<()> {
        let work_dir = out_dir("termination");
        simulate(SRC_FILES, "tb_termination", &work_dir)?;
        let output = read_to_string(work_dir.join("xrun.out"))?;
        assert_eq!(
            output.matches("Error").count(),
            0,
            "output should have no functionality errors"
        );
        Ok(())
    }

    #[test]
    fn des12() -> Result<()> {
        let work_dir = out_dir("des12");
        simulate(SRC_FILES, "tb_des12", &work_dir)?;
        Ok(())
    }

    #[test]
    fn tree_des32() -> Result<()> {
        let work_dir = out_dir("tree_des32");
        simulate(SRC_FILES, "tb_des", &work_dir)?;
        Ok(())
    }
}
