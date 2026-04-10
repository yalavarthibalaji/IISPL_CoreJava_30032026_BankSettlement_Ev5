package com.iispl.exception;

/**
 * TransactionNotFoundException — Thrown when a transaction is searched
 * but cannot be found in the database or in a list.
 *
 * This is a custom checked exception specific to our Bank Settlement System.
 *
 * WHEN IS THIS THROWN?
 *   - When user filters transactions by a reference number that does not exist
 *   - When user searches by txn ID that has no matching record
 *   - When the display utility tries to load a transaction that is missing
 *
 * HOW TO USE:
 *   throw new TransactionNotFoundException("No transaction found with ref: " + ref);
 *
 * WHY CHECKED (not RuntimeException)?
 *   Because the caller MUST handle it — if a transaction is missing,
 *   we cannot silently ignore it. The menu must show an error message.
 */
public class TransactionNotFoundException extends Exception {

    // serialVersionUID is needed because Exception implements Serializable
    private static final long serialVersionUID = 1L;

    // The reference or ID that was searched but not found
    private final String searchTerm;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Constructor with just a message.
     * Use when you want to describe what went wrong.
     *
     * @param message  Human-readable explanation of what was not found
     */
    public TransactionNotFoundException(String message) {
        super(message);
        this.searchTerm = "N/A";
    }

    /**
     * Constructor with message and the search term that caused the failure.
     * Use this when you know what the user searched for.
     *
     * @param message    Human-readable explanation
     * @param searchTerm The reference / ID that was not found
     */
    public TransactionNotFoundException(String message, String searchTerm) {
        super(message);
        this.searchTerm = searchTerm;
    }

    /**
     * Constructor with message and original cause (for wrapping lower-level errors).
     *
     * @param message Human-readable explanation
     * @param cause   The original exception that caused this
     */
    public TransactionNotFoundException(String message, Throwable cause) {
        super(message, cause);
        this.searchTerm = "N/A";
    }

    // -----------------------------------------------------------------------
    // Getter
    // -----------------------------------------------------------------------

    /**
     * Returns the search term (reference number or ID) that was not found.
     */
    public String getSearchTerm() {
        return searchTerm;
    }
}