use std::{
    fs::File,
    path::{Path, PathBuf},
    process::{Command, Stdio},
};

use anyhow::{Context, Result, anyhow, bail};
use tracing::info;

pub mod clocking;
pub mod primitives;
pub mod tx;

pub const VERILOG_SRC_DIR: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../verilog");

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
    let vcs_home = std::env::var("VCS_HOME").with_context(|| "invalid VCS_HOME")?;
    let verdi_home = std::env::var("VERDI_HOME").with_context(|| "invalid VCS_HOME")?;
    info!("VCS_HOME = {vcs_home}, VERDI_HOME = {verdi_home}");

    let mut vcs = Command::new("vcs")
        .args([
            "-full64",
            "-ams",
            "-sverilog",
            "-debug_access",
            "-o",
            "simv",
            "-top",
            tb,
        ])
        .args(src_files.into_iter().map(|f| f.into()))
        .current_dir(work_dir)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .with_context(|| "failed to run VCS")?;
    Command::new("tee")
        .arg(work_dir.join("vcs.out"))
        .current_dir(work_dir)
        .stdin(vcs.stdout.take().ok_or(anyhow!("VCS missing stdout"))?)
        .spawn()
        .with_context(|| "failed to spawn VCS output tee")?;
    Command::new("tee")
        .arg(work_dir.join("vcs.err"))
        .current_dir(work_dir)
        .stdin(vcs.stderr.take().ok_or(anyhow!("VCS missing stderr"))?)
        .spawn()
        .with_context(|| "failed to spawn VCS error tee")?;
    if !vcs
        .wait()
        .with_context(|| "failed to wait for VCS")?
        .success()
    {
        bail!("VCS exited with nonzero exit code");
    }

    let mut simv = Command::new("simv")
        .current_dir(work_dir)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .with_context(|| "failed to run simv")?;
    Command::new("tee")
        .arg(work_dir.join("simv.out"))
        .current_dir(work_dir)
        .stdin(simv.stdout.take().ok_or(anyhow!("VCS missing stdout"))?)
        .spawn()
        .with_context(|| "failed to spawn VCS output tee")?;
    Command::new("tee")
        .arg(work_dir.join("simv.err"))
        .current_dir(work_dir)
        .stdin(simv.stderr.take().ok_or(anyhow!("VCS missing stderr"))?)
        .spawn()
        .with_context(|| "failed to spawn VCS error tee")?;
    if !simv
        .wait()
        .with_context(|| "failed to wait for simv")?
        .success()
    {
        bail!("simv exited with nonzero exit code");
    }
    Ok(())
}
