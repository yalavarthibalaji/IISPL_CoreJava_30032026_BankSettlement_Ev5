package com.iispl.banksettlement.enums;

/**
 * Know Your Customer (KYC) verification status of a Customer.
 */
public enum KycStatus {
	PENDING, // KYC documents submitted but not yet reviewed
	VERIFIED, // KYC successfully verified
	REJECTED, // KYC verification failed/rejected
	EXPIRED, // KYC was valid but has now expired (re-verification needed)
	BLOCKED // Customer is blocked due to compliance/regulatory reasons
}
