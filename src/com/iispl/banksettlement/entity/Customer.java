package com.iispl.banksettlement.entity;

import com.iispl.banksettlement.enums.KycStatus;

import java.time.LocalDate;

/**
 * Customer — Represents a bank customer.
 *
 * During ingestion validation, the IngestionWorker checks whether the
 * customerId linked to the account exists and has KycStatus = VERIFIED.
 *
 * If the customer is not found or KYC is not VERIFIED, the transaction
 * is rejected with status FAILED.
 *
 * Extends BaseEntity: inherits id, createdAt, updatedAt, createdBy, version.
 */
public class Customer extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String firstName;
    private String lastName;
    private String email;

    // KYC verification status: PENDING, VERIFIED, REJECTED, EXPIRED, BLOCKED
    private KycStatus kycStatus;

    // Customer tier e.g. "RETAIL", "CORPORATE", "PREMIUM"
    private String customerTier;

    // Date when this customer was onboarded
    private LocalDate onboardingDate;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    public Customer() {
        super();
    }

    public Customer(String firstName, String lastName, String email,
                    KycStatus kycStatus, String customerTier,
                    LocalDate onboardingDate) {
        super();
        this.firstName      = firstName;
        this.lastName       = lastName;
        this.email          = email;
        this.kycStatus      = kycStatus;
        this.customerTier   = customerTier;
        this.onboardingDate = onboardingDate;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public KycStatus getKycStatus() { return kycStatus; }
    public void setKycStatus(KycStatus kycStatus) { this.kycStatus = kycStatus; }

    public String getCustomerTier() { return customerTier; }
    public void setCustomerTier(String customerTier) { this.customerTier = customerTier; }

    public LocalDate getOnboardingDate() { return onboardingDate; }
    public void setOnboardingDate(LocalDate onboardingDate) { this.onboardingDate = onboardingDate; }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "Customer{" +
               "id=" + getId() +
               ", firstName='" + firstName + '\'' +
               ", lastName='" + lastName + '\'' +
               ", email='" + email + '\'' +
               ", kycStatus=" + kycStatus +
               '}';
    }
}