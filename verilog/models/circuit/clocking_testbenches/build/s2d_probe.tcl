database -open s2d_waves -into s2d_waves.shm -default

# Probe all signals in the S2D testbench
probe -create -database s2d_waves -all -dynamic -depth all

# Run simulation for 10us
run 10us

exit
