use const_format::concatcp;

use crate::verilog::VERILOG_SRC_DIR;

pub const CLOCKING_SRC: &str = concatcp!(VERILOG_SRC_DIR, "/clocking.vams");
