database -open bbpll_waves -into bbpll_waves.shm -default

# Probe all signals in the BBPLL testbench
probe -create -database bbpll_waves -all -dynamic -depth all

# Run simulation for 100us (enough time for PLL to lock)
run 100us

exit
