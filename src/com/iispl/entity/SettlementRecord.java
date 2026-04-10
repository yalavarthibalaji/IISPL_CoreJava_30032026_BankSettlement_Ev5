package com.iispl.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.iispl.enums.SettlementStatus;

/**
 * SettlementRecord — Represents the result of settling ONE transaction within a
 * SettlementBatch.
 *
 * WHY THIS CLASS EXISTS: When a SettlementBatch runs, each transaction inside
 * it is settled individually. The result of settling each transaction is stored
 * as a SettlementRecord. Whether it succeeded or failed — both are recorded.
 *
 * COMPOSITION relationship with SettlementBatch: SettlementBatch HAS-A
 * List<SettlementRecord> A SettlementRecord belongs to exactly one
 * SettlementBatch.
 *
 * T3 teammate — Your SettlementEngine will CREATE these records after
 * processing each IncomingTransaction.
 */
public class SettlementRecord {

	// Unique identifier for this settlement record
	private Long recordId;

	// FK — which batch does this record belong to?
	private String batchId;

	// FK — which incoming transaction was settled?
	private Long incomingTxnId;

	// The actual amount that was settled
	// May differ from original amount in partial settlement scenarios
	private BigDecimal settledAmount;

	// Exact timestamp when this transaction was settled
	private LocalDateTime settledDate;

	// Result status of this settlement attempt
	private SettlementStatus settledStatus;

	// If settlement failed, the reason is stored here for investigation
	// null if settlement was successful
	private String failureReason;

	// -----------------------------------------------------------------------
	// Constructors
	// -----------------------------------------------------------------------

	/**
	 * Default constructor.
	 */
	public SettlementRecord() {
		this.settledDate = LocalDateTime.now();
		this.settledStatus = SettlementStatus.PENDING;
	}

	/**
	 * Parameterized constructor — use when creating a result after settlement.
	 *
	 * @param batchId       The batch this record belongs to
	 * @param incomingTxnId The transaction that was settled
	 * @param settledAmount How much was settled
	 * @param settledStatus Result: SETTLED, FAILED etc.
	 * @param failureReason null if success; reason string if failed
	 */
	public SettlementRecord(String batchId, Long incomingTxnId, BigDecimal settledAmount,
			SettlementStatus settledStatus, String failureReason) {
		this.batchId = batchId;
		this.incomingTxnId = incomingTxnId;
		this.settledAmount = settledAmount;
		this.settledStatus = settledStatus;
		this.failureReason = failureReason;
		this.settledDate = LocalDateTime.now();
	}

	// -----------------------------------------------------------------------
	// Getters and Setters
	// -----------------------------------------------------------------------

	public Long getRecordId() {
		return recordId;
	}

	public void setRecordId(Long recordId) {
		this.recordId = recordId;
	}

	public String getBatchId() {
		return batchId;
	}

	public void setBatchId(String batchId) {
		this.batchId = batchId;
	}

	public Long getIncomingTxnId() {
		return incomingTxnId;
	}

	public void setIncomingTxnId(Long incomingTxnId) {
		this.incomingTxnId = incomingTxnId;
	}

	public BigDecimal getSettledAmount() {
		return settledAmount;
	}

	public void setSettledAmount(BigDecimal settledAmount) {
		this.settledAmount = settledAmount;
	}

	public LocalDateTime getSettledDate() {
		return settledDate;
	}

	public void setSettledDate(LocalDateTime settledDate) {
		this.settledDate = settledDate;
	}

	public SettlementStatus getSettledStatus() {
		return settledStatus;
	}

	public void setSettledStatus(SettlementStatus settledStatus) {
		this.settledStatus = settledStatus;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public void setFailureReason(String failureReason) {
		this.failureReason = failureReason;
	}

	// -----------------------------------------------------------------------
	// toString
	// -----------------------------------------------------------------------

	@Override
	public String toString() {
		return "SettlementRecord{" + "recordId=" + recordId + ", batchId='" + batchId + '\'' + ", incomingTxnId="
				+ incomingTxnId + ", settledAmount=" + settledAmount + ", settledDate=" + settledDate
				+ ", settledStatus=" + settledStatus + ", failureReason='" + failureReason + '\'' + '}';
	}
}
