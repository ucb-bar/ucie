/*
    Description:
        Contains all the sideband message encodings for each field

    TODO: This can be combined into SidebandMessage.scala. Be sure to change the serializer
    and deserializer RTL. SidebandMessage.scala becomes SidebandPacketEncodings.scala
*/

package edu.berkeley.cs.uciedigital.sideband

import chisel3._

/* Opcode Identifer (unspecified values are reserved) */
object SBMsgOpcode extends ChiselEnum {

    val MemoryRead_32b = Value("b00000".U(5.W))

    val MemoryWrite_32b = Value("b00001".U(5.W))

    val DMSRegisterRead_32b = Value("b00010".U(5.W))

    val DMSRegisterWrite_32b = Value("b00011".U(5.W))

    val ConfigurationRead_32b = Value("b00100".U(5.W))

    val ConfigurationWrite_32b = Value("b00101".U(5.W))

    val MemoryRead_64b = Value("b01000".U(5.W))

    val MemoryWrite_64b = Value("b01001".U(5.W))

    val DMSRegisterRead_64b = Value("b01010".U(5.W))

    val DMSRegisterWrite_64b = Value("b01011".U(5.W))

    val ConfigurationRead_64b = Value("b01100".U(5.W))

    val ConfigurationWrite_64b = Value("b01101".U(5.W))

    val CompletionWithoutData = Value("b10000".U(5.W))

    val CompletionWith32bData = Value("b10001".U(5.W))

    val MessageWithoutData = Value("b10010".U(5.W))

    val ManagementPortMsgWithoutData = Value("b10111".U(5.W))

    val ManagementPortMsgWithData = Value("b11000".U(5.W))

    val CompletionWith64bData = Value("b11001".U(5.W))

    val MessageWith64bData = Value("b11011".U(5.W))

    /*
    --  See Section 4.1.5.2 (Spec Version: 3.0) for details on priority packets
    Snippet from spec:
        The opcode field in the priority packet can be 11111b or 11110b.
        * If the opcode is 11111b, the normal traffic packet resumes immediately 
          after the priority traffic packet without any idle time.          
        * If the opcode is 11110b, then another priority traffic packet is 
          transmitted immediately without any idle insertion.
    */
    val BackToBackPriorityPacket = Value("b11110".U(5.W))

    val SinglePriorityPacket = Value("b11111".U(5.W))
}
