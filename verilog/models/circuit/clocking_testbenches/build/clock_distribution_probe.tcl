database -open clock_distribution_waves -into clock_distribution_waves.shm -default

# Probe all signals in the clock distribution testbench
probe -create -database clock_distribution_waves -all -dynamic -depth all

# Run simulation for 10us
run 10us

exit
