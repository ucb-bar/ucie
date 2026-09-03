use std::{
    path::{Path, PathBuf},
    process::{Command, Stdio},
};

use anyhow::{Context, Result, anyhow, bail};
use const_format::concatcp;

pub mod afe;
pub mod phy;
pub mod primitives;
pub mod serdes;
pub mod training;

pub const VERILOG_SRC_DIR: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../verilog");
pub const CONSTANTS: &str = concatcp!(VERILOG_SRC_DIR, "/constants.vams");
pub const COMMON_DIR: &str = concatcp!(VERILOG_SRC_DIR, "/common");
pub const MODELS_DIR: &str = concatcp!(VERILOG_SRC_DIR, "/models");
pub const XCELIUM_DIR: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../xcelium");
pub const CONTROL_FILE: &str = concatcp!(XCELIUM_DIR, "/amscf.scs");
pub const PROBE_FILE: &str = concatcp!(XCELIUM_DIR, "/probe.tcl");

/// Abstraction level of the analog models the PHY is simulated against.
///
/// Every level provides the same analog cells with the same pins -- the
/// contract is written down in `verilog/README.md` -- so a level is selected by
/// nothing more than which directory under `verilog/models` is compiled
/// alongside `verilog/common`. Two levels cannot be compiled together, since
/// they define the same modules.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum Level {
    /// Highest abstraction: finite driver slew and a reference comparison, and
    /// no more than that. Enough to open an eye in front of the receiver and
    /// train against it, and cheap enough to do it in a full-stack simulation.
    Eye,
    /// Lowest abstraction: the front end as it is built, down to the
    /// switched-capacitor sampler and the segments of the drivers.
    Circuit,
}

impl Level {
    /// Every level, cheapest first. Tests that are meant to hold at any level
    /// iterate this.
    pub const ALL: [Level; 2] = [Level::Eye, Level::Circuit];

    pub fn dir_name(self) -> &'static str {
        match self {
            Level::Eye => "eye",
            Level::Circuit => "circuit",
        }
    }

    pub fn dir(self) -> PathBuf {
        PathBuf::from(MODELS_DIR).join(self.dir_name())
    }
}

fn sources_under(dir: impl AsRef<Path>) -> Vec<PathBuf> {
    let dir = dir.as_ref().display().to_string();
    let mut files: Vec<PathBuf> = ["sv", "v", "vams"]
        .iter()
        .flat_map(|ext| {
            let pattern = format!("{dir}/**/*.{ext}");
            glob::glob(&pattern)
                .into_iter()
                .flatten()
                .filter_map(|entry| entry.ok()) // drop bad paths
                .filter(|p| p.is_file())
        })
        .collect();
    // xrun compiles in the order it is given files and a package has to be
    // compiled before whatever imports it, so `*_pkg.sv` goes first. Nothing
    // else here has an ordering requirement.
    files.sort_by_key(|p| {
        !p.file_name()
            .and_then(|n| n.to_str())
            .is_some_and(|n| n.ends_with("_pkg.sv"))
    });
    files
}

/// Sources that are the same at every level: the digital structure of the
/// tiles, the PHY that wires them together, and the benches written against the
/// model contract.
pub fn common_src_files() -> Vec<PathBuf> {
    sources_under(COMMON_DIR)
}

/// The analog models of one level.
pub fn model_src_files(level: Level) -> Vec<PathBuf> {
    sources_under(level.dir())
}

/// Everything needed to simulate the PHY at `level`.
pub fn get_src_files(level: Level) -> Vec<PathBuf> {
    let mut files = common_src_files();
    files.extend(model_src_files(level));
    files
}

pub fn simulate(
    src_files: impl IntoIterator<Item = impl Into<PathBuf>>,
    tb: impl AsRef<str>,
    work_dir: impl AsRef<Path>,
) -> Result<()> {
    let tb = tb.as_ref();
    let work_dir = work_dir.as_ref();
    std::fs::create_dir_all(work_dir).with_context(|| "failed to create work dir")?;
    let xcelium_home =
        PathBuf::from(std::env::var("XCELIUM_HOME").with_context(|| "invalid XCELIUM_HOME")?);
    let disciplines = xcelium_home.join("tools.lnx86/spectre/etc/ahdl/disciplines.vams");
    let constants = xcelium_home.join("tools.lnx86/spectre/etc/ahdl/constants.vams");

    let cp = Command::new("cp")
        .arg("-r")
        .arg(PathBuf::from(VERILOG_SRC_DIR).join("."))
        .arg(work_dir.join("."))
        .current_dir(work_dir)
        .status()
        .with_context(|| "failed to run cp")?;

    if !cp.success() {
        bail!("cp exited with nonzero exit code");
    }

    let mut xrun = Command::new("xrun")
        .args([
            "-allowredefinition",
            "-dmsaoi",
            "-sv_ms",
            "-timescale",
            "1ps/100fs",
            "-spectre_args",
            "+preset=mx +mt=32 -ahdllint=warn",
            "-access",
            "+rwc",
            "-top",
            tb,
            "-input",
            PROBE_FILE,
        ])
        .arg(disciplines)
        .arg(constants)
        .arg(CONSTANTS)
        .args(src_files.into_iter().map(|f| f.into()))
        .arg(CONTROL_FILE)
        .current_dir(work_dir)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .with_context(|| "failed to run xrun")?;
    Command::new("tee")
        .arg(work_dir.join("xrun.out"))
        .current_dir(work_dir)
        .stdin(xrun.stdout.take().ok_or(anyhow!("xrun missing stdout"))?)
        .spawn()
        .with_context(|| "failed to spawn xrun output tee")?;
    Command::new("tee")
        .arg(work_dir.join("xrun.err"))
        .current_dir(work_dir)
        .stdin(xrun.stderr.take().ok_or(anyhow!("xrun missing stderr"))?)
        .spawn()
        .with_context(|| "failed to spawn xrun error tee")?;
    if !xrun
        .wait()
        .with_context(|| "failed to wait for xrun")?
        .success()
    {
        bail!("xrun exited with nonzero exit code");
    }
    Ok(())
}

/// Defines one `#[test]` per abstraction level, each running `$tb` and failing
/// on any error the bench printed.
///
/// Benches written against the model contract in `verilog/README.md` have to
/// hold at every level, and this is what makes that a test rather than a claim.
/// A new level needs an arm here as well as a variant on [`Level`]: a macro
/// cannot walk [`Level::ALL`] to name the functions it generates.
#[cfg(test)]
macro_rules! bench_at_every_level {
    ($($name:ident => $tb:expr),* $(,)?) => {
        $(
            mod $name {
                use anyhow::Result;
                use test_log::test;

                use crate::verilog::{Level, harness::expect_clean};

                #[test]
                fn eye() -> Result<()> {
                    expect_clean(Level::Eye, $tb)
                }

                #[test]
                fn circuit() -> Result<()> {
                    expect_clean(Level::Circuit, $tb)
                }
            }
        )*
    };
}

#[cfg(test)]
pub(crate) use bench_at_every_level;

#[cfg(test)]
pub(crate) mod harness {
    use std::fs::read_to_string;

    use anyhow::Result;

    use super::{Level, get_src_files, simulate};
    use crate::tests::out_dir;

    /// Runs `tb` against `level`'s models and returns everything xrun printed.
    ///
    /// The work directory carries the level, so the two levels of one bench do
    /// not overwrite each other's logs.
    pub fn run(level: Level, tb: &str) -> Result<String> {
        let work_dir = out_dir(format!("{}_{}", tb, level.dir_name()));
        simulate(get_src_files(level), tb, &work_dir)?;
        Ok(read_to_string(work_dir.join("xrun.out"))?)
    }

    /// Runs `tb` and fails if it printed anything the benches use to report a
    /// mismatch.
    pub fn expect_clean(level: Level, tb: &str) -> Result<()> {
        let output = run(level, tb)?;
        assert_eq!(
            output.matches("Error").count(),
            0,
            "{tb} at the {} level should have no functionality errors",
            level.dir_name()
        );
        Ok(())
    }
}
