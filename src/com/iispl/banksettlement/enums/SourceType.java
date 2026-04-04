package com.iispl.banksettlement.enums;

/**
 * Represents all external/internal source systems that send transactions.
 *
 * CHANGE LOG:
 *   - SWIFT removed: system scope is now Indian domestic banks only.
 *     Cross-border (SWIFT / MT103) is not supported in this version.
 */
public enum SourceType {
    CBS,       // Core Banking System
    RTGS,      // Real-Time Gross Settlement
    NEFT,      // National Electronic Funds Transfer
    UPI,       // Unified Payments Interface
    FINTECH,   // Third-party Fintech APIs
    INTERNAL   // Internal system transactions
}