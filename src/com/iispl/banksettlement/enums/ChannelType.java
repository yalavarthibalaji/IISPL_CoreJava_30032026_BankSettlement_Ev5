package com.iispl.banksettlement.enums;

/**
 * Payment/settlement channel through which a SettlementInstruction is sent.
 */
public enum ChannelType {
    RTGS,     // Real-Time Gross Settlement (high-value, immediate)
    NEFT,     // National Electronic Funds Transfer (batch, domestic)
    UPI,      // Unified Payments Interface (instant, retail)
    SWIFT,    // Society for Worldwide Interbank Financial Telecommunication (cross-border)
    ACH,      // Automated Clearing House (batch electronic transfers)
    INTERNAL  // Internal transfer within the same bank/system
}
