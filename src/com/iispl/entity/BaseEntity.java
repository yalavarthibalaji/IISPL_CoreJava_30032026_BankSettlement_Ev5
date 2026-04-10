package com.iispl.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * BaseEntity — Abstract superclass for ALL persistent domain classes.
 *
 * Every entity in the system (Account, Customer, Transaction, SettlementBatch
 * etc.) extends this class to inherit common audit fields.
 *
 * Implements: - Auditable : provides createdAt, updatedAt, createdBy -
 * Serializable: allows objects to be serialized (required for queue handoff)
 *
 * T2, T3 teammates — extend this class in ALL your entity classes.
 */
public abstract class BaseEntity implements Auditable, Serializable {

	// Serializable requires this — keeps version consistent across the team
	private static final long serialVersionUID = 1L;

	// Primary key — every table will have this as the unique identifier
	private Long id;

	// Timestamp when this record was first created
	private LocalDateTime createdAt;

	// Timestamp of the most recent update to this record
	private LocalDateTime updatedAt;

	// Username or system name that created this record
	private String createdBy;

	// Optimistic locking version — prevents two threads overwriting each other
	private int version;

	// -----------------------------------------------------------------------
	// Constructor
	// -----------------------------------------------------------------------

	/**
	 * Default constructor. Sets createdAt and updatedAt to current time
	 * automatically. createdBy must be set explicitly by the calling code.
	 */
	public BaseEntity() {
		this.createdAt = LocalDateTime.now();
		this.updatedAt = LocalDateTime.now();
		this.version = 0;
	}

	// -----------------------------------------------------------------------
	// Getters and Setters
	// -----------------------------------------------------------------------

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	@Override
	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	@Override
	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	/**
	 * Call this method every time you update any field on a subclass. This keeps
	 * the updatedAt timestamp accurate.
	 */
	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

	@Override
	public String getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(String createdBy) {
		this.createdBy = createdBy;
	}

	public int getVersion() {
		return version;
	}

	public void setVersion(int version) {
		this.version = version;
	}

	// -----------------------------------------------------------------------
	// toString — useful for logging and debugging
	// -----------------------------------------------------------------------

	@Override
	public String toString() {
		return "BaseEntity{" + "id=" + id + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + ", createdBy='"
				+ createdBy + '\'' + ", version=" + version + '}';
	}
}
