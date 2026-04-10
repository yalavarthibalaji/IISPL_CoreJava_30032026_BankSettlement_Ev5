package com.iispl.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.iispl.enums.BatchStatus;

/**
 * SettlementBatch — Groups multiple transactions together for batch settlement.
 *
 * WHY BATCH SETTLEMENT? Instead of settling each transaction one by one (which
 * is slow and expensive), the settlement engine groups many transactions into a
 * batch and settles them together. This is how real banking systems like NEFT
 * work — they run in cycles and settle all transactions in that cycle as one
 * batch.
 *
 * IS-A BaseEntity (extends BaseEntity) Inherits: id, createdAt, updatedAt,
 * createdBy, version
 *
 * HAS-A List<SettlementRecord> — COMPOSITION relationship A SettlementBatch
 * contains a list of SettlementRecord objects. Each SettlementRecord represents
 * one individual transaction that was settled as part of this batch. If the
 * batch is deleted, all its records are also deleted (composition).
 *
 * T3 teammate — you will add to the records list from your SettlementEngine.
 */
public class SettlementBatch extends BaseEntity {

	private static final long serialVersionUID = 1L;

	// Unique batch identifier — format: "BATCH-YYYYMMDD-001"
	// Example: "BATCH-20240615-001"
	private String batchId;

	// The date for which this batch is settling transactions
	private LocalDate batchDate;

	// Current status of this batch (SCHEDULED, RUNNING, COMPLETED, FAILED etc.)
	private BatchStatus batchStatus;

	// Total number of transactions included in this batch
	private int totalTransactions;

	// Total monetary amount across ALL transactions in this batch
	private BigDecimal totalAmount;

	// HAS-A COMPOSITION: List of individual settlement records in this batch
	// Each record = one transaction's settlement result
	private List<SettlementRecord> records;

	// Username/system name that triggered/ran this batch
	private String runBy;

	// Exact timestamp when this batch was executed
	private LocalDateTime runAt;

	// -----------------------------------------------------------------------
	// Constructors
	// -----------------------------------------------------------------------

	/**
	 * Default constructor. Status starts as SCHEDULED. Records list is initialized
	 * empty. Total amounts start at ZERO.
	 */
	public SettlementBatch() {
		super();
		this.batchStatus = BatchStatus.SCHEDULED;
		this.records = new ArrayList<>();
		this.totalTransactions = 0;
		this.totalAmount = BigDecimal.ZERO;
	}

	/**
	 * Parameterized constructor — use when creating a batch with known details.
	 *
	 * @param batchId   Unique batch ID e.g. "BATCH-20240615-001"
	 * @param batchDate Date for which this batch is settling
	 * @param runBy     Who/what is running this batch
	 */
	public SettlementBatch(String batchId, LocalDate batchDate, String runBy) {
		super();
		this.batchId = batchId;
		this.batchDate = batchDate;
		this.runBy = runBy;
		this.batchStatus = BatchStatus.SCHEDULED;
		this.records = new ArrayList<>();
		this.totalTransactions = 0;
		this.totalAmount = BigDecimal.ZERO;
		this.runAt = LocalDateTime.now();
	}

	// -----------------------------------------------------------------------
	// Helper method to add a SettlementRecord to this batch
	// -----------------------------------------------------------------------

	/**
	 * Adds a SettlementRecord to this batch and updates the totals. Call this each
	 * time a transaction is settled and added to the batch.
	 *
	 * @param record The SettlementRecord to add
	 */
	public void addRecord(SettlementRecord record) {
		if (record != null) {
			this.records.add(record);
			this.totalTransactions = this.records.size();
			// Add this record's settled amount to the running total
			if (record.getSettledAmount() != null) {
				this.totalAmount = this.totalAmount.add(record.getSettledAmount());
			}
		}
	}

	// -----------------------------------------------------------------------
	// Getters and Setters
	// -----------------------------------------------------------------------

	public String getBatchId() {
		return batchId;
	}

	public void setBatchId(String batchId) {
		this.batchId = batchId;
	}

	public LocalDate getBatchDate() {
		return batchDate;
	}

	public void setBatchDate(LocalDate batchDate) {
		this.batchDate = batchDate;
	}

	public BatchStatus getBatchStatus() {
		return batchStatus;
	}

	public void setBatchStatus(BatchStatus batchStatus) {
		this.batchStatus = batchStatus;
	}

	public int getTotalTransactions() {
		return totalTransactions;
	}

	public void setTotalTransactions(int totalTransactions) {
		this.totalTransactions = totalTransactions;
	}

	public BigDecimal getTotalAmount() {
		return totalAmount;
	}

	public void setTotalAmount(BigDecimal totalAmount) {
		this.totalAmount = totalAmount;
	}

	public List<SettlementRecord> getRecords() {
		return records;
	}

	public void setRecords(List<SettlementRecord> records) {
		this.records = records;
		// Keep totalTransactions in sync
		this.totalTransactions = (records != null) ? records.size() : 0;
	}

	public String getRunBy() {
		return runBy;
	}

	public void setRunBy(String runBy) {
		this.runBy = runBy;
	}

	public LocalDateTime getRunAt() {
		return runAt;
	}

	public void setRunAt(LocalDateTime runAt) {
		this.runAt = runAt;
	}

	// -----------------------------------------------------------------------
	// toString
	// -----------------------------------------------------------------------

	@Override
	public String toString() {
		return "SettlementBatch{" + "batchId='" + batchId + '\'' + ", batchDate=" + batchDate + ", batchStatus="
				+ batchStatus + ", totalTransactions=" + totalTransactions + ", totalAmount=" + totalAmount
				+ ", runBy='" + runBy + '\'' + ", runAt=" + runAt + '}';
	}
}
