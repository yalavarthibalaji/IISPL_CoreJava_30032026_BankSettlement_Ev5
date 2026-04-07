package com.iispl.banksettlement.enums;

/**
 * Tracks the lifecycle/processing stage of an IncomingTransaction from receipt
 * through to final processing.
 */
public enum ProcessingStatus {
	RECEIVED, // Raw payload received from source system
	VALIDATED, // Payload has passed validation checks
	QUEUED, // Placed in the BlockingQueue for processing
	PROCESSING, // Currently being processed by a consumer thread
	PROCESSED, // Successfully processed and handed off to settlement
	FAILED, // Processing failed due to an error
	DEAD_LETTER // Moved to dead-letter queue after repeated failures
}
