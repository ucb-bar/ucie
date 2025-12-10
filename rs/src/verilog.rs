use std::{
    path::{Path, PathBuf},
    process::Command,
};

use anyhow::{Context, Result, bail};
use tracing::info;

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

pub fn simulate(tb: impl AsRef<str>, work_dir: impl AsRef<Path>) -> Result<()> {
    let tb = tb.as_ref();
    let work_dir = work_dir.as_ref();
    std::fs::create_dir_all(work_dir).with_context(|| "failed to create work dir")?;
    let vcs_home = std::env::var("VCS_HOME").with_context(|| "invalid VCS_HOME")?;
    let verdi_home = std::env::var("VERDI_HOME").with_context(|| "invalid VCS_HOME")?;
    info!("VCS_HOME = {vcs_home}, VERDI_HOME = {verdi_home}");
    let source_files = get_src_files();
    println!("{work_dir:?}");
    let status = Command::new("vcs")
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
        .args(source_files)
        .current_dir(work_dir)
        .status()
        .with_context(|| "failed to run VCS")?;
    if !status.success() {
        bail!("errors encountered in VCS");
    }
    let status = Command::new("simv")
        .current_dir(work_dir)
        .status()
        .with_context(|| "failed to run simulation executable")?;
    if !status.success() {
        bail!("errors encountered in simulation executable");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use anyhow::Result;
    use test_log::test;

    use crate::{tests::out_dir, verilog::simulate};

    #[test]
    fn serializer() -> Result<()> {
        simulate("tb_tree_serializer", out_dir("serializer"))
    }
}
