# Equivalent to gcc options:
# -mcmodel=medany -Wl,--start-group -lc_nano -lgloss_htif -Wl,--end-group -lgcc -static -nostartfiles -dT htif.ld

%include <nano.specs>

%rename cc1 htif_nano_cc1
%rename link htif_nano_link
%rename startfile htif_nano_startfile
%rename endfile htif_nano_endfile

*cc1:
%(htif_nano_cc1) -mcmodel=medany

*nano_libgloss:
%{specs=nosys.specs:-lnosys} %{!specs=nosys.specs:-lgloss_htif}

# FIXME: %T sequence is not used to specify a linker script since it
# does not search the current working directory
*link:
%(htif_nano_link) %:replace-outfile(-lgloss -lgloss_htif) -dT htif.ld%s -static

*startfile:


*endfile:
