pub mod verilog;

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    pub const BUILD_DIR: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/build");

    pub fn out_dir(test_name: impl AsRef<str>) -> PathBuf {
        let test_name = test_name.as_ref();
        PathBuf::from(BUILD_DIR).join(test_name)
    }
}
