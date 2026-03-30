package com.iispl.banksettlement.enums;

/**
 * Status of a SettlementInstruction sent to a payment rail/channel.
 */
public enum InstructionStatus {
    CREATED,     // Instruction object created, not yet sent
    SENT,        // Instruction dispatched to the payment channel
    ACKNOWLEDGED,// Payment channel acknowledged receipt
    PROCESSING,  // Channel is processing the instruction
    COMPLETED,   // Instruction successfully executed
    FAILED,      // Instruction failed at the channel level
    CANCELLED    // Instruction was cancelled before execution
}
