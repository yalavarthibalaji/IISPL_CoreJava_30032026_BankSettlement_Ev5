package com.iispl.banksettlement.entity;

import com.iispl.banksettlement.enums.ProtocolType;

/**
 * SourceSystem — Represents an external system that sends transactions
 * into the bank settlement pipeline.
 *
 * Examples: CBS (Core Banking), RTGS, SWIFT, NEFT, UPI, Fintech APIs.
 *
 * This is a HAS-A component inside IncomingTransaction.
 * Every incoming transaction must know which source system sent it.
 *
 * Extends BaseEntity to inherit: id, createdAt, updatedAt, createdBy, version.
 */
public class SourceSystem extends BaseEntity {

    private static final long serialVersionUID = 1L;

    // Unique identifier for this source system record
    private Long sourceSystemId;

    // Short code identifying the system e.g. "CBS", "RTGS", "SWIFT"
    private String systemCode;

    // Protocol this source system uses to send transactions
    private ProtocolType protocol;

    // Connection details stored as a JSON string
    // e.g. {"url":"http://cbs.bank.com/api","timeout":30}
    private String connectionConfig;

    // Whether this source system is currently active and sending transactions
    private boolean isActive;

    // Contact email for the team responsible for this source system
    private String contactEmail;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Default constructor — required for creating empty objects
     * before setting fields one by one.
     */
    public SourceSystem() {
        super();
    }

    /**
     * Parameterized constructor — use this when you have all details ready.
     *
     * @param systemCode      Short code like "CBS", "RTGS", "SWIFT"
     * @param protocol        How this system connects (REST, FILE, MQ etc.)
     * @param connectionConfig JSON string with connection details
     * @param isActive        Is this system currently active?
     * @param contactEmail    Email of the team managing this system
     */
    public SourceSystem(String systemCode, ProtocolType protocol,
                        String connectionConfig, boolean isActive,
                        String contactEmail) {
        super(); // calls BaseEntity() — sets createdAt and updatedAt
        this.systemCode       = systemCode;
        this.protocol         = protocol;
        this.connectionConfig = connectionConfig;
        this.isActive         = isActive;
        this.contactEmail     = contactEmail;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public Long getSourceSystemId() {
        return sourceSystemId;
    }

    public void setSourceSystemId(Long sourceSystemId) {
        this.sourceSystemId = sourceSystemId;
    }

    public String getSystemCode() {
        return systemCode;
    }

    public void setSystemCode(String systemCode) {
        this.systemCode = systemCode;
    }

    public ProtocolType getProtocol() {
        return protocol;
    }

    public void setProtocol(ProtocolType protocol) {
        this.protocol = protocol;
    }

    public String getConnectionConfig() {
        return connectionConfig;
    }

    public void setConnectionConfig(String connectionConfig) {
        this.connectionConfig = connectionConfig;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "SourceSystem{" +
               "sourceSystemId=" + sourceSystemId +
               ", systemCode='" + systemCode + '\'' +
               ", protocol=" + protocol +
               ", isActive=" + isActive +
               ", contactEmail='" + contactEmail + '\'' +
               '}';
    }
}
