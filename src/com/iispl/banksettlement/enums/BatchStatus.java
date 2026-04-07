package com.iispl.banksettlement.enums;

/**
 * Tracks the lifecycle status of a SettlementBatch.
 */
public enum BatchStatus {
	SCHEDULED, // Batch is scheduled but not yet started
	RUNNING, // Batch is actively being processed
	COMPLETED, // All records in the batch processed successfully
	FAILED, // Batch processing encountered a critical failure
	PARTIAL, // Batch completed but with some failed records
	CANCELLED // Batch was cancelled before completion
}
