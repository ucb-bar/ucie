`timescale 1ns/1ps

module ucie_d2d_dv_top;
  import ucie_d2d_dv_pkg::*;

  logic clock;
  logic reset;

  ucie_d2d_fdi_if #(
    .DATA_BITS(DATA_BITS),
    .SIDEBAND_WIDTH(SIDEBAND_WIDTH)
  ) fdi();

  ucie_d2d_rdi_if #(
    .DATA_BITS(DATA_BITS),
    .SIDEBAND_WIDTH(SIDEBAND_WIDTH)
  ) rdi();

  D2DAdapterDvTop dut (
    .clock                  (clock),
    .reset                  (reset),
    .io_fdi_lclk            (fdi.lclk),
    .io_fdi_lpIrdy          (fdi.lpIrdy),
    .io_fdi_lpValid         (fdi.lpValid),
    .io_fdi_lpData          (fdi.lpData),
    .io_fdi_plTrdy          (fdi.plTrdy),
    .io_fdi_plValid         (fdi.plValid),
    .io_fdi_plData          (fdi.plData),
    .io_fdi_lpStateReq      (fdi.lpStateReq),
    .io_fdi_lpLinkError     (fdi.lpLinkError),
    .io_fdi_plStateSts      (fdi.plStateSts),
    .io_fdi_plInbandPres    (fdi.plInbandPres),
    .io_fdi_plError         (fdi.plError),
    .io_fdi_plCError        (fdi.plCError),
    .io_fdi_plNfError       (fdi.plNfError),
    .io_fdi_plTrainError    (fdi.plTrainError),
    .io_fdi_plPhyInRecenter (fdi.plPhyInRecenter),
    .io_fdi_plStallReq      (fdi.plStallReq),
    .io_fdi_lpStallAck      (fdi.lpStallAck),
    .io_fdi_plSpeedmode     (fdi.plSpeedmode),
    .io_fdi_plMaxSpeedmode  (fdi.plMaxSpeedmode),
    .io_fdi_plLnkCfg        (fdi.plLnkCfg),
    .io_fdi_plClkReq        (fdi.plClkReq),
    .io_fdi_plWakeAck       (fdi.plWakeAck),
    .io_fdi_plCfg           (fdi.plCfg),
    .io_fdi_plCfgVld        (fdi.plCfgVld),
    .io_fdi_plCfgCrd        (fdi.plCfgCrd),
    .io_fdi_lpCfg           (fdi.lpCfg),
    .io_fdi_lpCfgVld        (fdi.lpCfgVld),
    .io_fdi_lpCfgCrd        (fdi.lpCfgCrd),
    .io_rdi_lclk            (rdi.lclk),
    .io_rdi_lpIrdy          (rdi.lpIrdy),
    .io_rdi_lpValid         (rdi.lpValid),
    .io_rdi_lpData          (rdi.lpData),
    .io_rdi_plTrdy          (rdi.plTrdy),
    .io_rdi_plValid         (rdi.plValid),
    .io_rdi_plData          (rdi.plData),
    .io_rdi_lpStateReq      (rdi.lpStateReq),
    .io_rdi_lpLinkError     (rdi.lpLinkError),
    .io_rdi_plStateSts      (rdi.plStateSts),
    .io_rdi_plInbandPres    (rdi.plInbandPres),
    .io_rdi_plError         (rdi.plError),
    .io_rdi_plCError        (rdi.plCError),
    .io_rdi_plNfError       (rdi.plNfError),
    .io_rdi_plTrainError    (rdi.plTrainError),
    .io_rdi_plPhyInRecenter (rdi.plPhyInRecenter),
    .io_rdi_plStallReq      (rdi.plStallReq),
    .io_rdi_lpStallAck      (rdi.lpStallAck),
    .io_rdi_plSpeedmode     (rdi.plSpeedmode),
    .io_rdi_plMaxSpeedmode  (rdi.plMaxSpeedmode),
    .io_rdi_plLnkCfg        (rdi.plLnkCfg),
    .io_rdi_plClkReq        (rdi.plClkReq),
    .io_rdi_lpClkAck        (rdi.lpClkAck),
    .io_rdi_lpWakeReq       (rdi.lpWakeReq),
    .io_rdi_plWakeAck       (rdi.plWakeAck),
    .io_rdi_plCfg           (rdi.plCfg),
    .io_rdi_plCfgVld        (rdi.plCfgVld),
    .io_rdi_plCfgCrd        (rdi.plCfgCrd),
    .io_rdi_lpCfg           (rdi.lpCfg),
    .io_rdi_lpCfgVld        (rdi.lpCfgVld),
    .io_rdi_lpCfgCrd        (rdi.lpCfgCrd)
  );

  ucie_d2d_smoke_checkers checkers (
    .clock (clock),
    .reset (reset),
    .fdi   (fdi),
    .rdi   (rdi)
  );

  initial begin
    clock = 1'b0;
    forever #5 clock = ~clock;
  end

  initial begin
    fdi.drive_idle();
    rdi.drive_idle();
    reset = 1'b1;
    repeat (5) @(posedge clock);
    reset = 1'b0;

    rdi.plInbandPres = 1'b1;
    repeat (5) @(posedge clock);
    rdi.plStateSts = RDI_STATE_ACTIVE;
    repeat (20) @(posedge clock);

    $display("D2D DV smoke completed");
    $finish;
  end

  initial begin
    repeat (1000) @(posedge clock);
    $fatal(1, "D2D DV smoke timeout");
  end
endmodule
