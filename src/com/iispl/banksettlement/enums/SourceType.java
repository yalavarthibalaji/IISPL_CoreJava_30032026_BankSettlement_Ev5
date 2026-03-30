package com.iispl.banksettlement.enums;

/**
 * Represents all external/internal source systems that send transactions.
 */
public enum SourceType {
    CBS,       // Core Banking System
    RTGS,      // Real-Time Gross Settlement
    SWIFT,     // Cross-border international transfers
    NEFT,      // National Electronic Funds Transfer
    UPI,       // Unified Payments Interface
    FINTECH,   // Third-party Fintech APIs
    INTERNAL   // Internal system transactions
}
