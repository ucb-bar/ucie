//! The bench that trains a lane: sweeps the sampling point and the slicing
//! reference, and checks the eye it finds is a real one.
//!
//! Run at every level. What it asserts is the property that separates a model
//! good enough to train against from one that is not -- some codes have to
//! fail -- so a level that stopped modelling either the driver's slew or the
//! reference comparison would fail here rather than silently pass everything.

#[cfg(test)]
mod tests {
    use anyhow::Result;
    use test_log::test;

    use crate::verilog::{Level, harness::run};

    fn train(level: Level) -> Result<()> {
        let output = run(level, "training_tb")?;
        assert!(
            output.contains("Training complete."),
            "training bench at the {} level did not finish",
            level.dir_name()
        );
        assert_eq!(
            output.matches("Error").count(),
            0,
            "training bench at the {} level reported an error",
            level.dir_name()
        );
        Ok(())
    }

    #[test]
    fn eye() -> Result<()> {
        train(Level::Eye)
    }

    #[test]
    fn circuit() -> Result<()> {
        train(Level::Circuit)
    }
}
