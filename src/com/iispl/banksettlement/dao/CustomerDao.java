package com.iispl.banksettlement.dao;

import com.iispl.banksettlement.entity.Customer;

/**
 * CustomerDao — Interface for Customer database operations.
 *
 * Used during ingestion validation to check if the customer
 * linked to an account exists and has KYC status = VERIFIED.
 *
 * RULE: Interface declares WHAT to do.
 *       CustomerDaoImpl decides HOW using JDBC.
 */
public interface CustomerDao {

    /**
     * Finds a Customer by their primary key (id).
     * Returns null if not found.
     *
     * @param customerId The customer's primary key
     * @return Customer if found, null if not found
     */
    Customer findById(Long customerId);

    /**
     * Checks if a customer exists and has KycStatus = VERIFIED.
     * This is the main validation method used during ingestion.
     *
     * @param customerId The customer's primary key
     * @return true if customer exists and kycStatus = VERIFIED, false otherwise
     */
    boolean isCustomerKycVerified(Long customerId);
}