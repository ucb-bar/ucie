database -open dcdl_waves -into dcdl_waves.shm -default

# Probe all signals in the DCDL testbench
probe -create -database dcdl_waves -all -dynamic -depth all

# Run simulation for 10us
run 10us

exit
