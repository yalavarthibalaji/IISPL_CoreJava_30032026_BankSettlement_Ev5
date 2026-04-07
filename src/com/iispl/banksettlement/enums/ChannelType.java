package com.iispl.banksettlement.enums;

/**
 * Payment/settlement channel through which a SettlementInstruction is sent.
 *
 * CHANGE LOG: - SWIFT removed: system scope is now Indian domestic banks only.
 */
public enum ChannelType {
	RTGS, // Real-Time Gross Settlement (high-value, immediate)
	NEFT, // National Electronic Funds Transfer (batch, domestic)
	UPI, // Unified Payments Interface (instant, retail)
	ACH, // Automated Clearing House (batch electronic transfers)
	INTERNAL // Internal transfer within the same bank/system
}