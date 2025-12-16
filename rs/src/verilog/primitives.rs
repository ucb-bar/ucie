use const_format::concatcp;

use crate::verilog::VERILOG_SRC_DIR;

pub const PRIMITIVES_SV_SRC: &str = concatcp!(VERILOG_SRC_DIR, "/primitives.sv");
pub const PRIMITIVES_VAMS_SRC: &str = concatcp!(VERILOG_SRC_DIR, "/primitives.vams");

#[cfg(test)]
mod tests {
    use std::fs::read_to_string;

    use anyhow::Result;
    use regex::Regex;
    use test_log::test;

    use crate::{
        tests::out_dir,
        verilog::{
            primitives::{PRIMITIVES_SV_SRC, PRIMITIVES_VAMS_SRC},
            simulate,
        },
    };

    #[test]
    fn dff() -> Result<()> {
        // TODO: Improve checks.
        let re = Regex::new(
            "\
                (?s)\
                .*Testing setup violation\
                .*Timing violation\
                \\s*\\$setup\
                .*Testing hold violation\
                .*Timing violation\
                \\s*\\$hold\
                .*Normal operation\
                .*\\$finish.*\
            ",
        )
        .unwrap();
        let work_dir = out_dir("dff");
        simulate([PRIMITIVES_SV_SRC], "tb_dff", &work_dir)?;
        let output = read_to_string(work_dir.join("xrun.out"))?;
        assert!(
            re.is_match(&output),
            "output should have one setup violation followed by a hold violation"
        );
        assert_eq!(
            output.matches("Timing violation").count(),
            2,
            "output should have 2 violations"
        );
        Ok(())
    }

    #[test]
    fn latch() -> Result<()> {
        // TODO: Improve checks.
        let re_p = Regex::new(
            "\
                (?s)\
                .*Testing setup violation\
                .*Timing violation\
                \\s*\\$setup\
                .*Scope:\\s*tb_latch\\.pdut\
                .*Testing hold violation\
                .*Timing violation\
                \\s*\\$hold\
                .*Scope:\\s*tb_latch\\.pdut\
                .*Normal operation\
                .*\\$finish.*\
            ",
        )
        .unwrap();
        let re_n = Regex::new(
            "\
                (?s)\
                .*Testing setup violation\
                .*Timing violation\
                \\s*\\$setup\
                .*Scope:\\s*tb_latch\\.ndut\
                .*Testing hold violation\
                .*Timing violation\
                \\s*\\$hold\
                .*Scope:\\s*tb_latch\\.ndut\
                .*Normal operation\
                .*\\$finish.*\
            ",
        )
        .unwrap();
        let work_dir = out_dir("latch");
        simulate([PRIMITIVES_SV_SRC], "tb_latch", &work_dir)?;
        let output = read_to_string(work_dir.join("xrun.out"))?;
        assert!(
            re_p.is_match(&output),
            "output should have one setup violation followed by a hold violation for pos_latch"
        );
        assert!(
            re_n.is_match(&output),
            "output should have one setup violation followed by a hold violation for neg_latch"
        );
        assert_eq!(
            output.matches("Timing violation").count(),
            4,
            "output should have 4 violations"
        );
        Ok(())
    }

    #[test]
    fn rdac() -> Result<()> {
        let work_dir = out_dir("rdac");
        simulate([PRIMITIVES_VAMS_SRC], "tb_rdac", &work_dir)?;
        let output = read_to_string(work_dir.join("xrun.out"))?;
        assert_eq!(
            output.matches("Error").count(),
            0,
            "output should have no functionality errors"
        );
        Ok(())
    }

    #[test]
    fn inv_selfbias() -> Result<()> {
        let work_dir = out_dir("inv_selfbias");
        simulate([PRIMITIVES_VAMS_SRC], "tb_inv_selfbias", &work_dir)?;
        let output = read_to_string(work_dir.join("xrun.out"))?;
        assert_eq!(
            output.matches("Error").count(),
            0,
            "output should have no functionality errors"
        );
        Ok(())
    }

    #[test]
    fn inv_discharge_cap() -> Result<()> {
        let work_dir = out_dir("inv_discharge_cap");
        simulate([PRIMITIVES_VAMS_SRC], "tb_inv_discharge_cap", &work_dir)?;
        let output = read_to_string(work_dir.join("xrun.out"))?;
        assert_eq!(
            output.matches("Error").count(),
            0,
            "output should have no functionality errors"
        );
        Ok(())
    }
}
