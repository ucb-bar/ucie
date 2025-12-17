use std::{
    path::{Path, PathBuf},
    process::{Command, Stdio},
};

use anyhow::{Context, Result, anyhow, bail};
use const_format::concatcp;

pub mod primitives;
pub mod rx;
pub mod tx;

pub const VERILOG_SRC_DIR: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../verilog");
pub const CONSTANTS: &str = concatcp!(VERILOG_SRC_DIR, "/constants.sv");
pub const XCELIUM_DIR: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../xcelium");
pub const CONTROL_FILE: &str = concatcp!(XCELIUM_DIR, "/amscf.scs");
pub const PROBE_FILE: &str = concatcp!(XCELIUM_DIR, "/probe.tcl");

pub fn get_src_files() -> Vec<PathBuf> {
    ["sv", "v", "vams"]
        .iter()
        .flat_map(|ext| {
            let pattern = format!("{VERILOG_SRC_DIR}/**/*.{ext}");
            glob::glob(&pattern)
                .into_iter()
                .flatten()
                .filter_map(|entry| entry.ok()) // drop bad paths
                .filter(|p| p.is_file())
        })
        .collect()
}

pub fn simulate(
    src_files: impl IntoIterator<Item = impl Into<PathBuf>>,
    tb: impl AsRef<str>,
    work_dir: impl AsRef<Path>,
) -> Result<()> {
    let tb = tb.as_ref();
    let work_dir = work_dir.as_ref();
    std::fs::create_dir_all(work_dir).with_context(|| "failed to create work dir")?;
    let xcelium_home = std::env::var("XCELIUM_HOME").with_context(|| "invalid XCELIUM_HOME")?;
    let disciplines =
        PathBuf::from(xcelium_home).join("tools.lnx86/spectre/etc/ahdl/disciplines.vams");

    let mut xrun = Command::new("xrun")
        .args([
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
