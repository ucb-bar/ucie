use const_format::concatcp;

use crate::verilog::VERILOG_SRC_DIR;

pub const RX_SRC: &str = concatcp!(VERILOG_SRC_DIR, "/rx.vams");

#[cfg(test)]
mod tests {
    use std::fs::read_to_string;

    use anyhow::Result;
    use test_log::test;

    use crate::{
        tests::out_dir,
        verilog::{
            primitives::{PRIMITIVES_SV_SRC, PRIMITIVES_VAMS_SRC},
            rx::RX_SRC,
            simulate,
        },
    };

    const SRC_FILES: &[&str] = &[RX_SRC, PRIMITIVES_SV_SRC, PRIMITIVES_VAMS_SRC];

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
}
