database -open waves -into waves.shm -default

#   probe all
probe -create -database waves -all -dynamic -depth all
run 100e6
