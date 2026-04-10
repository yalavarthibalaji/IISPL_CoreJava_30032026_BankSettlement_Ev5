package com.iispl.exception;

/**
 * AccountNotFoundException — Thrown when a bank account or NPCI member account
 * is searched but cannot be found in the database.
 *
 * This is a custom checked exception specific to our Bank Settlement System.
 *
 * WHEN IS THIS THROWN?
 *   - When user filters NPCI accounts by bank name that does not exist
 *   - When the display utility tries to load an account that is missing
 *   - When netting or reconciliation references an account that is not in DB
 *
 * HOW TO USE:
 *   throw new AccountNotFoundException("No NPCI account found for bank: " + bankName);
 *
 * WHY CHECKED (not RuntimeException)?
 *   Because the caller MUST handle it — missing account means the
 *   display or pipeline cannot proceed and must show a clear error.
 */
public class AccountNotFoundException extends Exception {

    private static final long serialVersionUID = 1L;

    // The bank name or account ID that was searched but not found
    private final String searchTerm;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Constructor with just a message.
     *
     * @param message  Human-readable explanation of what was not found
     */
    public AccountNotFoundException(String message) {
        super(message);
        this.searchTerm = "N/A";
    }

    /**
     * Constructor with message and the search term that caused the failure.
     *
     * @param message    Human-readable explanation
     * @param searchTerm The bank name / account ID that was not found
     */
    public AccountNotFoundException(String message, String searchTerm) {
        super(message);
        this.searchTerm = searchTerm;
    }

    /**
     * Constructor with message and original cause.
     *
     * @param message Human-readable explanation
     * @param cause   The original exception that caused this
     */
    public AccountNotFoundException(String message, Throwable cause) {
        super(message, cause);
        this.searchTerm = "N/A";
    }

    // -----------------------------------------------------------------------
    // Getter
    // -----------------------------------------------------------------------

    /**
     * Returns the search term (bank name or account ID) that was not found.
     */
    public String getSearchTerm() {
        return searchTerm;
    }
}