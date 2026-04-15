package edu.berkeley.cs.uciedigital.d2dadapter
import chisel3._
//import chisel3.util._
//import chisel3.experimental._

import edu.berkeley.cs.uciedigital.sideband._
import edu.berkeley.cs.uciedigital.interfaces._

class ParityGeneratorIO(fdiParams: FdiParams) extends Bundle{
    val snd_data = Input(Vec(fdiParams.width, UInt(8.W)))
    //val snd_data = Input(Bits((8 * fdiParams.width).W))
    val snd_data_vld = Input(Bool())
    val rcv_data = Input(Vec(fdiParams.width, UInt(8.W)))
    //val rcv_data = Input(Bits((8 * fdiParams.width).W))
    val rcv_data_vld = Input(Bool())

    val parity_data = Output(Vec(fdiParams.width, UInt(8.W)))
    //val parity_data = Output(Bits((8 * fdiParams.width).W))
    val parity_insert = Output(Bool())
    val parity_check = Output(Bool())
    val parity_rdy = Input(Bool())
    
    val parity_check_result = Output(Vec(ParityAmount.PARITY_DATA_NBYTE_4, Bool()))
    val parity_check_result_valid = Output(Bool())
    val rdi_state = Input(PhyState())

    val parity_rx_enable = Input(Bool())// tell parity if the parity should be used for receiving
    val parity_tx_enable = Input(Bool())// tell parity if the parity should be used for sending
    val parity_n = Input(UInt(ParityGeneratorWidth.PARITY_N_WIDTH))

}

class ParityGenerator(fdiParams: FdiParams) extends Module{
    val io = IO(new ParityGeneratorIO(fdiParams))

    val parity_data_snd_reg = RegInit(VecInit(Seq.fill(ParityAmount.PARITY_DATA_NBYTE_4)(false.B)))// all parity data
    val parity_data_rcv_reg = RegInit(VecInit(Seq.fill(ParityAmount.PARITY_DATA_NBYTE_4)(false.B)))// all parity data

    val parity_dcount_snd_reg = RegInit(0.U(19.W)) // number of data has sent by the protocol
    val parity_pcount_snd_reg = RegInit(0.U(9.W)) // number of parity has sent
    val parity_dcount_rcv_reg = RegInit(0.U(19.W)) // number of data has received from the phy
    val parity_pcount_rcv_reg = RegInit(0.U(9.W)) //num of parity has checked

    parity_data_snd_reg := parity_data_snd_reg
    parity_data_rcv_reg := parity_data_rcv_reg
    parity_dcount_snd_reg := parity_dcount_snd_reg
    parity_pcount_snd_reg := parity_pcount_snd_reg
    parity_dcount_rcv_reg := parity_dcount_rcv_reg
    parity_pcount_rcv_reg := parity_pcount_rcv_reg

    val n_64 = Wire(UInt(9.W))
    val n_256_256 = Wire(UInt(19.W))

    when(io.parity_n === ParityN.ONE){
        n_64 := ParityAmount.PARITY_DATA_NBYTE_1.U
        n_256_256 := ParityAmount.DATA_NBYTE_1.U
    }.elsewhen(io.parity_n === ParityN.TWO){
        n_64 := ParityAmount.PARITY_DATA_NBYTE_2.U
        n_256_256 := ParityAmount.DATA_NBYTE_2.U
    }.elsewhen(io.parity_n === ParityN.FOUR){
        n_64 := ParityAmount.PARITY_DATA_NBYTE_4.U
        n_256_256 := ParityAmount.DATA_NBYTE_4.U
    }.otherwise{
        n_64 := ParityAmount.PARITY_DATA_NBYTE_1.U
        n_256_256 := ParityAmount.DATA_NBYTE_1.U
    }

    // snd data add parity
    when(io.rdi_state =/= PhyState.active){
        for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_4){
            parity_data_snd_reg(i) := 0.U
        }
        parity_dcount_snd_reg := 0.U
        parity_pcount_snd_reg := 0.U        
    }.elsewhen(parity_pcount_snd_reg + fdiParams.width.U === n_64 && io.parity_rdy){// all parity data are sent, reset 
        for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_4){
            parity_data_snd_reg(i) := 0.U
        }
        parity_dcount_snd_reg := 0.U
        parity_pcount_snd_reg := 0.U
    }.elsewhen(io.snd_data_vld && io.parity_tx_enable && parity_dcount_snd_reg =/= n_256_256){// new data come in
        when(io.parity_n === ParityN.ONE){
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_1 - fdiParams.width){
                parity_data_snd_reg(i) := parity_data_snd_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1))

            }
            for (i <- ParityAmount.PARITY_DATA_NBYTE_1 - fdiParams.width until ParityAmount.PARITY_DATA_NBYTE_1){
                parity_data_snd_reg(i) := parity_data_snd_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1)) ^ io.snd_data((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1)).xorR
            }
            for(i <- ParityAmount.PARITY_DATA_NBYTE_1 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_snd_reg(i) := parity_data_snd_reg(i)
            }
        }.elsewhen(io.parity_n === ParityN.TWO){
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_2 - fdiParams.width){
                parity_data_snd_reg(i) := parity_data_snd_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_2))

            }
            for (i <- ParityAmount.PARITY_DATA_NBYTE_2 - fdiParams.width until ParityAmount.PARITY_DATA_NBYTE_2){
                parity_data_snd_reg(i) := parity_data_snd_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_2)) ^ io.snd_data((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1)).xorR
            }
            for(i <- ParityAmount.PARITY_DATA_NBYTE_2 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_snd_reg(i) := parity_data_snd_reg(i)
            }
        }.elsewhen(io.parity_n === ParityN.FOUR){
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_4 - fdiParams.width){
                parity_data_snd_reg(i) := parity_data_snd_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_4))

            }
            for (i <- ParityAmount.PARITY_DATA_NBYTE_4 - fdiParams.width until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_snd_reg(i) := parity_data_snd_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_4)) ^ io.snd_data((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1)).xorR
            }
            for(i <- ParityAmount.PARITY_DATA_NBYTE_4 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_snd_reg(i) := parity_data_snd_reg(i)
            }
        }   
        parity_dcount_snd_reg := parity_dcount_snd_reg + fdiParams.width.U
        parity_pcount_snd_reg := 0.U
    }.elsewhen(parity_dcount_snd_reg === n_256_256 && io.parity_rdy){// data is sent
        when(io.parity_n === ParityN.ONE){
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_1){
                parity_data_snd_reg(i) := parity_data_snd_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1))

            }
            for(i <- ParityAmount.PARITY_DATA_NBYTE_1 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_snd_reg(i) := parity_data_snd_reg(i)
            }
        }.elsewhen(io.parity_n === ParityN.TWO){
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_2){
                parity_data_snd_reg(i) := parity_data_snd_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_2))

            }
            for(i <- ParityAmount.PARITY_DATA_NBYTE_2 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_snd_reg(i) := parity_data_snd_reg(i)
            }
        }.elsewhen(io.parity_n === ParityN.FOUR){
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_snd_reg(i) := parity_data_snd_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_4))

            }
            for(i <- ParityAmount.PARITY_DATA_NBYTE_4 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_snd_reg(i) := parity_data_snd_reg(i)
            }
        }   
        parity_dcount_snd_reg := n_256_256
        parity_pcount_snd_reg := parity_pcount_snd_reg + fdiParams.width.U      
    }

    for( i <- 0 until fdiParams.width){
        io.parity_data(i) := parity_data_snd_reg(i)
    }
    io.parity_insert := parity_dcount_snd_reg === n_256_256
    // rcv data, check parity
    val parity_check_result_valid_reg = RegInit(false.B)
    io.parity_check_result_valid := parity_check_result_valid_reg

    when(io.rdi_state =/= PhyState.active){
        for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_4){
            parity_data_rcv_reg(i) := 0.U
        }
        parity_dcount_rcv_reg := 0.U
        parity_pcount_rcv_reg := 0.U   
        parity_check_result_valid_reg := false.B
    }.elsewhen(parity_pcount_rcv_reg + fdiParams.width.U === n_64 && io.rcv_data_vld && parity_dcount_rcv_reg === n_256_256){//last data to check
        for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_4){
            parity_data_rcv_reg(i) := 0.U
        }
        parity_dcount_rcv_reg := 0.U
        parity_pcount_rcv_reg := 0.U
        parity_check_result_valid_reg := true.B
    }.elsewhen(io.rcv_data_vld && io.parity_rx_enable && parity_dcount_rcv_reg =/= n_256_256){// new data come in
        when(io.parity_n === ParityN.ONE){
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_1 - fdiParams.width){
                parity_data_rcv_reg(i) := parity_data_rcv_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1))

            }
            for (i <- ParityAmount.PARITY_DATA_NBYTE_1 - fdiParams.width until ParityAmount.PARITY_DATA_NBYTE_1){
                parity_data_rcv_reg(i) := parity_data_rcv_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1)) ^ io.rcv_data((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1)).xorR
            }
            for(i <- ParityAmount.PARITY_DATA_NBYTE_1 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_rcv_reg(i) := parity_data_rcv_reg(i)
            }
        }.elsewhen(io.parity_n === ParityN.TWO){
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_2 - fdiParams.width){
                parity_data_rcv_reg(i) := parity_data_rcv_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_2))

            }
            for (i <- ParityAmount.PARITY_DATA_NBYTE_2 - fdiParams.width until ParityAmount.PARITY_DATA_NBYTE_2){
                parity_data_rcv_reg(i) := parity_data_rcv_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_2)) ^ io.rcv_data((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1)).xorR
            }
            for(i <- ParityAmount.PARITY_DATA_NBYTE_2 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_rcv_reg(i) := parity_data_rcv_reg(i)
            }
        }.elsewhen(io.parity_n === ParityN.FOUR){
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_4 - fdiParams.width){
                parity_data_rcv_reg(i) := parity_data_rcv_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_4))

            }
            for (i <- ParityAmount.PARITY_DATA_NBYTE_4 - fdiParams.width until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_rcv_reg(i) := parity_data_rcv_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_4)) ^ io.rcv_data((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1)).xorR
            }
            for(i <- ParityAmount.PARITY_DATA_NBYTE_4 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_rcv_reg(i) := parity_data_rcv_reg(i)
            }
        }   
        parity_dcount_rcv_reg := parity_dcount_rcv_reg + fdiParams.width.U
        parity_pcount_rcv_reg := 0.U
        parity_check_result_valid_reg := false.B
    }.elsewhen(parity_dcount_rcv_reg === n_256_256 && io.rcv_data_vld){

        when(io.parity_n === ParityN.ONE){
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_1){
                parity_data_rcv_reg(i) := parity_data_rcv_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1))

            }
            for(i <- ParityAmount.PARITY_DATA_NBYTE_1 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_rcv_reg(i) := parity_data_rcv_reg(i)
            }
        }.elsewhen(io.parity_n === ParityN.TWO){
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_2){
                parity_data_rcv_reg(i) := parity_data_rcv_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_2))

            }
            for(i <- ParityAmount.PARITY_DATA_NBYTE_2 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_rcv_reg(i) := parity_data_rcv_reg(i)
            }
        }.elsewhen(io.parity_n === ParityN.FOUR){
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_rcv_reg(i) := parity_data_rcv_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_4))

            }
            for(i <- ParityAmount.PARITY_DATA_NBYTE_4 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_data_rcv_reg(i) := parity_data_rcv_reg(i)
            }
        } 

        parity_dcount_rcv_reg := n_256_256
        parity_pcount_rcv_reg := parity_pcount_rcv_reg + fdiParams.width.U      
        parity_check_result_valid_reg := false.B
    }.otherwise{
        parity_data_rcv_reg := parity_data_rcv_reg
        parity_dcount_rcv_reg := parity_dcount_rcv_reg
        parity_pcount_rcv_reg := parity_pcount_rcv_reg     
        parity_check_result_valid_reg := false.B
    }

    io.parity_check := (parity_dcount_rcv_reg === n_256_256)

    val parity_check_bits_reg = RegInit(VecInit(Seq.fill(ParityAmount.PARITY_DATA_NBYTE_4)(false.B)))

    io.parity_check_result := parity_check_bits_reg
    
    when(parity_dcount_rcv_reg === n_256_256 && io.rcv_data_vld){// this data should be checked{
        when(io.parity_n === ParityN.ONE){
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_1 - fdiParams.width){
                parity_check_bits_reg(i) := parity_check_bits_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1))
            }
            for( i <- ParityAmount.PARITY_DATA_NBYTE_1 - fdiParams.width until ParityAmount.PARITY_DATA_NBYTE_1){
                parity_check_bits_reg(i) := (parity_data_rcv_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1)) =/= io.rcv_data((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1)))
            }
            for( i <- ParityAmount.PARITY_DATA_NBYTE_1 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_check_bits_reg(i) := parity_check_bits_reg(i)
            }
        }.elsewhen(io.parity_n === ParityN.TWO){
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_2 - fdiParams.width){
                parity_check_bits_reg(i) := parity_check_bits_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_2))
            }
            for( i <- ParityAmount.PARITY_DATA_NBYTE_2 - fdiParams.width until ParityAmount.PARITY_DATA_NBYTE_2){
                parity_check_bits_reg(i) := parity_data_rcv_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_2)) =/= io.rcv_data((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1))
            }
            for( i <- ParityAmount.PARITY_DATA_NBYTE_2 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_check_bits_reg(i) := parity_check_bits_reg(i)
            }
        }.elsewhen(io.parity_n === ParityN.FOUR){
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_2 - fdiParams.width){
                parity_check_bits_reg(i) := parity_check_bits_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_2))
            }
            for( i <- ParityAmount.PARITY_DATA_NBYTE_2 - fdiParams.width until ParityAmount.PARITY_DATA_NBYTE_2){
                parity_check_bits_reg(i) := parity_data_rcv_reg((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_2)) =/= io.rcv_data((i + fdiParams.width) % (ParityAmount.PARITY_DATA_NBYTE_1))
            }
            for( i <- ParityAmount.PARITY_DATA_NBYTE_2 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_check_bits_reg(i) := parity_check_bits_reg(i)
            }
        }.otherwise{
            for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_4){
                parity_check_bits_reg(i) := parity_check_bits_reg(i)
            }                
        }

    }.otherwise{
        for( i <- 0 until ParityAmount.PARITY_DATA_NBYTE_4){
            parity_check_bits_reg(i) := parity_check_bits_reg(i)
        }        
    }



}