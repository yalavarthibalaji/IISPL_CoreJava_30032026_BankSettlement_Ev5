package com.iispl.adapter;

import com.iispl.entity.IncomingTransaction;
import com.iispl.enums.SourceType;

/**
 * TransactionAdapter — Interface that ALL source system adapters must
 * implement.
 *
 * DESIGN PATTERN USED: Strategy Pattern Each source system (CBS, RTGS, SWIFT,
 * NEFT, UPI, Fintech) has its own wire format. Instead of writing one giant
 * if-else block, we give each source system its own adapter class that
 * implements this interface.
 *
 * The AdapterRegistry picks the right adapter at runtime based on SourceType.
 * Adding a new source system = just write a new adapter class. Nothing else
 * changes.
 *
 * CONTRACT: Every adapter must: 1. adapt() -- Convert the raw payload to a
 * canonical IncomingTransaction 2. getSourceType() -- Declare which source
 * system it handles
 *
 * TEAMMATES (T4 threading): Your IngestionWorker/AdapterThread will call
 * adapt() and put the result into the BlockingQueue<IncomingTransaction>.
 */
public interface TransactionAdapter {

	/**
	 * Converts a raw payload string from a source system into a standardised
	 * IncomingTransaction object.
	 *
	 * @param rawPayload The raw message/payload as received from the source system
	 * @return A fully populated IncomingTransaction in canonical format
	 * @throws IllegalArgumentException if the payload is null, empty or malformed
	 */
	IncomingTransaction adapt(String rawPayload);

	/**
	 * Returns the SourceType enum value this adapter is responsible for. Used by
	 * AdapterRegistry to look up the correct adapter at runtime.
	 *
	 * @return SourceType (CBS, RTGS, SWIFT, NEFT, UPI, or FINTECH)
	 */
	SourceType getSourceType();
}
