#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include "mmio.h"
#include "ucie.h"

/*
 * UCIe simple loopback test: program the UCIe MMIO registers (mirrors
 * `Codegen.tlSimpleRegReqs`), put the offchip router in bypass mode so every
 * outgoing offchip request is forwarded to the UCIe port, then issue 10
 * writes followed by 10 reads against an offchip-tagged copy of a local
 * buffer. The UCIe link is wired in loopback at the chip top, so the data
 * round-trips: out via UCIe TX, back via UCIe RX, untagged by the receive
 * side's `ChipletAddressTranslator`, and back to local DRAM.
 *
 * Build via the Makefile in this directory; run via the
 * `should run ucie-simple.riscv` case in UcieRocketChipSpec.
 */

#define UCIE_REG_BASE       0x8000UL  /* ucie_control@8000 */
#define ROUTER_BASE         0x4000UL  /* offchip routing-table region */

/* Three control registers packed after the routing table.
 * tableEntries=4, regsPerEntry=4, beatBytes=8 → control regs at +0x80. */
#define ROUTER_CHIP_ID      (ROUTER_BASE + 0x80)
#define ROUTER_ROUTING_MODE (ROUTER_BASE + 0x88)
#define ROUTER_BYPASS_PORT  (ROUTER_BASE + 0x90)

#define LOCAL_CHIP_ID       1UL
/* topBits = log2Ceil(MaxOffchipAddressRange.base) = log2Ceil(0x800000000) = 35.
 * A request with top bits == LOCAL_CHIP_ID gets its tag stripped on the
 * receive side, landing at the local address. */
#define OFFCHIP_OFFSET      (LOCAL_CHIP_ID * 0x800000000UL)

#define N 10

static uint64_t buf[N];

int main(void) {
  printf("UCIe simple test: starting (chip_id=%lu)\n", (unsigned long)LOCAL_CHIP_ID);

  /* 1. Program UCIe PHY registers and switch the mainband into TL mode. */
  setup_ucie(UCIE_REG_BASE);

  /* 2. Tell this chip's offchip router its own chip ID, then flip into
   *    bypass mode so the routing table is ignored. With a single port,
   *    bypass_port = 0 selects the UCIe port (also the default).        */
  reg_write64(ROUTER_CHIP_ID,      LOCAL_CHIP_ID);
  reg_write64(ROUTER_BYPASS_PORT,  0);
  reg_write64(ROUTER_ROUTING_MODE, 0);

  /* 3. Build a remote pointer: byte-add OFFCHIP_OFFSET so the top bits of
   *    the resulting address equal LOCAL_CHIP_ID. The local-side router
   *    forwards it over UCIe; the remote-side translator (which is the same
   *    chip in loopback) strips the tag and re-presents it as a local
   *    DRAM access at the original physical offset of `buf`.             */
  volatile uint64_t *remote =
      (volatile uint64_t *)((uint8_t *)buf + OFFCHIP_OFFSET);

  /* 4. 10 writes. */
  for (int i = 0; i < N; i++) {
    remote[i] = 0xdeadbeef00000000UL | (uint64_t)i;
  }

  /* 5. 10 reads, comparing against the values we just wrote. */
  for (int i = 0; i < N; i++) {
    uint64_t got = remote[i];
    uint64_t want = 0xdeadbeef00000000UL | (uint64_t)i;
    if (got != want) {
      printf("FAIL at %d: got 0x%lx, want 0x%lx\n", i, (unsigned long)got, (unsigned long)want);
      exit(1);
    }
  }

  printf("UCIe simple test: passed (%d writes, %d reads)\n", N, N);
  return 0;
}
