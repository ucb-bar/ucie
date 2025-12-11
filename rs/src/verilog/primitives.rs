use const_format::concatcp;

use crate::verilog::VERILOG_SRC_DIR;

pub const PRIMITIVES_SRC: &str = concatcp!(VERILOG_SRC_DIR, "/primitives.vams");

#[cfg(test)]
mod tests {
    use std::fs::read_to_string;

    use anyhow::Result;
    use regex::Regex;
    use test_log::test;

    use crate::{
        tests::out_dir,
        verilog::{primitives::PRIMITIVES_SRC, simulate},
    };

    #[test]
    fn dff() -> Result<()> {
        // TODO: Improve checks.
        let re = Regex::new(
            "\
                (?s)\
                .*Testing setup violation\
                .*Timing violation in tb_dff\\.dut\
                \\s*\\$setup\
                .*Testing hold violation\
                .*Timing violation in tb_dff\\.dut\
                \\s*\\$hold\
                .*Normal operation\
                .*\\$finish.*\
            ",
        )
        .unwrap();
        let work_dir = out_dir("dff");
        simulate([PRIMITIVES_SRC], "tb_dff", &work_dir)?;
        let output = read_to_string(work_dir.join("simv.out"))?;
        println!("{}", output);
        assert!(
            re.is_match(&output),
            "output should have one setup violation followed by a hold violation"
        );
        assert_eq!(
            output.matches("Timing violation").count(),
            2,
            "output should have 2 violations"
        );
        assert_eq!(
            output.matches("ERROR").count(),
            0,
            "output should have no functionality errors"
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
                .*Timing violation in tb_latch\\.pdut\
                \\s*\\$setup\
                .*Testing hold violation\
                .*Timing violation in tb_latch\\.pdut\
                \\s*\\$hold\
                .*Normal operation\
                .*\\$finish.*\
            ",
        )
        .unwrap();
        let re_n = Regex::new(
            "\
                (?s)\
                .*Testing setup violation\
                .*Timing violation in tb_latch\\.ndut\
                \\s*\\$setup\
                .*Testing hold violation\
                .*Timing violation in tb_latch\\.ndut\
                \\s*\\$hold\
                .*Normal operation\
                .*\\$finish.*\
            ",
        )
        .unwrap();
        let work_dir = out_dir("latch");
        simulate([PRIMITIVES_SRC], "tb_latch", &work_dir)?;
        let output = read_to_string(work_dir.join("simv.out"))?;
        println!("{}", output);
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
        assert_eq!(
            output.matches("Error").count(),
            0,
            "output should have no functionality errors"
        );
        Ok(())
    }
}
