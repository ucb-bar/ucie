//! Benches for the primitives the tiles and the analog models are built from.
//!
//! The digital ones -- the flop and the latches, with their setup and hold
//! checks -- live in `verilog/common` and behave the same whatever level is
//! compiled next to them, so they run at the cheapest level only. The analog
//! ones exist because `models/circuit` builds its sampler out of them, so they
//! only run there.

#[cfg(test)]
mod tests {
    use anyhow::Result;
    use regex::Regex;
    use test_log::test;

    use crate::verilog::{
        Level,
        harness::{expect_clean, run},
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
        let output = run(Level::Eye, "dff_tb")?;
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
                .*Scope:\\s*latch_tb\\.pdut\
                .*Testing hold violation\
                .*Timing violation\
                \\s*\\$hold\
                .*Scope:\\s*latch_tb\\.pdut\
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
                .*Scope:\\s*latch_tb\\.ndut\
                .*Testing hold violation\
                .*Timing violation\
                \\s*\\$hold\
                .*Scope:\\s*latch_tb\\.ndut\
                .*Normal operation\
                .*\\$finish.*\
            ",
        )
        .unwrap();
        let output = run(Level::Eye, "latch_tb")?;
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
    fn inv_selfbias() -> Result<()> {
        expect_clean(Level::Circuit, "inv_selfbias_tb")
    }

    #[test]
    fn inv_discharge_cap() -> Result<()> {
        expect_clean(Level::Circuit, "inv_discharge_cap_tb")
    }
}
