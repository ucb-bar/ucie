# UCIe IP

An open-source implementation of the UCIe 3.0 specification.

You can request a copy of the UCIe specification [here](https://www.uciexpress.org/3-0-spec-download).

## Tests
> [!NOTE]
> For now to use `mill` please run the following to include the appropriate dependencies.
>```bash
>git clone https://github.com/ucb-substrate/chippy.git
>cd chippy
>git submodule update --init --recursive
>./mill __.publishLocal
>```

To run the RTL tests, make sure Scala is installed.

Then, run the following from the `scala/` folder.

```bash
./mill test
```

To run the VAMS tests, make sure the `XCELIUM_HOME` environment variable is correctly set and `xrun` is on your `PATH`.

Then, run the following from the `rs/` folder:

```bash
cargo t
```

## Organization

Chisel RTL for all digital components can be found in the `scala/` directory.

Verilog testbenches and AMS models can be found in the `verilog/` folder.

Rust code for orchestrating tests can be found in the `rs/` folder.

## Contributing

If you'd like to contribute, please let us know. You can:

- Open an issue.
- Email rahulkumar@berkeley.edu and rohankumar@berkeley.edu.
  
Documentation updates, tests, and bugfixes are always welcome.
For larger feature additions, please discuss your ideas with us before implementing them.

Contributions can be submitted by opening a pull request against the main branch of this repository.

Unless you explicitly state otherwise, any contribution intentionally submitted for inclusion in the work by you shall be licensed under the BSD 3-Clause license, without any additional terms or conditions.

