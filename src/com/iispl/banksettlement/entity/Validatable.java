package com.iispl.banksettlement.entity;

/**
 * Validatable interface — provides a contract for field-level validation.
 *
 * Any domain class that needs to validate its own state before
 * being persisted or processed should implement this interface.
 */
public interface Validatable {

    /**
     * Returns true if all mandatory fields are valid.
     * Returns false if any required field is missing or invalid.
     */
    boolean isValid();

    /**
     * Returns a human-readable error message describing
     * what is invalid. Returns null or empty string if valid.
     */
    String getValidationError();
}
