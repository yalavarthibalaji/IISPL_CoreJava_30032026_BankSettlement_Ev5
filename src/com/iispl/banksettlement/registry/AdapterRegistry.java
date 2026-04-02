package com.iispl.banksettlement.registry;

import com.iispl.banksettlement.adapter.CbsAdapter;
import com.iispl.banksettlement.adapter.FintechAdapter;
import com.iispl.banksettlement.adapter.NeftAdapter;
import com.iispl.banksettlement.adapter.RtgsAdapter;
import com.iispl.banksettlement.adapter.SwiftAdapter;
import com.iispl.banksettlement.adapter.TransactionAdapter;
import com.iispl.banksettlement.adapter.UpiAdapter;
import com.iispl.banksettlement.enums.SourceType;

import java.util.HashMap;
import java.util.Map;

/**
 * AdapterRegistry — Central registry that holds all TransactionAdapter instances
 * and provides a lookup method to find the correct adapter at runtime.
 *
 * HOW IT WORKS:
 *   Internally uses a Map<SourceType, TransactionAdapter>.
 *   When a transaction arrives, the calling code (IngestionWorker) passes the
 *   SourceType and gets back the appropriate adapter.
 *
 * DESIGN PATTERN: Registry + Strategy
 *   - Registry: central store of available adapters
 *   - Strategy: the selected adapter defines how to parse the raw payload
 *
 * CHANGE FROM PREVIOUS VERSION:
 *   Previously NeftUpiAdapter was one class handling both NEFT and UPI with
 *   a shared CSV format. That was a placeholder format.
 *
 *   Now NEFT and UPI are two completely separate adapter classes:
 *     - NeftAdapter  → handles SourceType.NEFT — fixed-width batch file format
 *     - UpiAdapter   → handles SourceType.UPI  — JSON REST webhook format
 *   Each is registered independently in this registry.
 *
 * HOW TO ADD A NEW SOURCE SYSTEM IN THE FUTURE:
 *   1. Write a new adapter class implementing TransactionAdapter
 *   2. Register it here with one line: registerAdapter(new YourNewAdapter())
 *   No other code changes needed anywhere.
 *
 * TEAMMATE USAGE (T4 — Threading):
 *   AdapterRegistry registry = new AdapterRegistry();
 *   TransactionAdapter adapter = registry.getAdapter(SourceType.CBS);
 *   IncomingTransaction txn = adapter.adapt(rawPayload);
 *   blockingQueue.put(txn);
 */
public class AdapterRegistry {

    // The core map: SourceType → TransactionAdapter
    // HashMap is fine here because the registry is built once at startup
    // and only read (never written to) after that.
    private final Map<SourceType, TransactionAdapter> adapterMap;

    // -----------------------------------------------------------------------
    // Constructor — registers all known adapters at startup
    // -----------------------------------------------------------------------

    /**
     * Creates the registry and auto-registers all 6 source system adapters.
     * Called once during application startup (inside IngestionPipeline constructor).
     *
     * Adapters registered:
     *   CBS     → CbsAdapter     (pipe-delimited flat file, 10 fields)
     *   RTGS    → RtgsAdapter    (XML over MQ, 14 fields)
     *   SWIFT   → SwiftAdapter   (MT103 message, 14 fields)
     *   NEFT    → NeftAdapter    (fixed-width batch file, 10 fields)
     *   UPI     → UpiAdapter     (JSON REST webhook, 14 fields, no account validation)
     *   FINTECH → FintechAdapter (proprietary JSON, 18 fields including nested)
     */
    public AdapterRegistry() {
        adapterMap = new HashMap<>();

        // Register all 6 adapters — each handles its own source system
        registerAdapter(new CbsAdapter());
        registerAdapter(new RtgsAdapter());
        registerAdapter(new SwiftAdapter());
        registerAdapter(new NeftAdapter());   // NEFT — fixed-width format (separate from UPI)
        registerAdapter(new UpiAdapter());    // UPI  — JSON webhook format (separate from NEFT)
        registerAdapter(new FintechAdapter());

        System.out.println("AdapterRegistry: Registered adapters for: " + adapterMap.keySet());
    }

    // -----------------------------------------------------------------------
    // Core methods
    // -----------------------------------------------------------------------

    /**
     * Registers an adapter in the registry.
     * Uses the adapter's own getSourceType() as the map key.
     *
     * @param adapter The TransactionAdapter to register (must not be null)
     */
    public void registerAdapter(TransactionAdapter adapter) {
        if (adapter == null) {
            throw new IllegalArgumentException(
                "AdapterRegistry: Cannot register a null adapter"
            );
        }
        adapterMap.put(adapter.getSourceType(), adapter);
    }

    /**
     * Retrieves the correct TransactionAdapter for a given SourceType.
     *
     * @param sourceType The source system type (CBS, RTGS, SWIFT, NEFT, UPI, FINTECH)
     * @return           The matching TransactionAdapter
     * @throws IllegalArgumentException if no adapter is registered for the given SourceType
     */
    public TransactionAdapter getAdapter(SourceType sourceType) {
        if (sourceType == null) {
            throw new IllegalArgumentException(
                "AdapterRegistry: sourceType cannot be null"
            );
        }

        TransactionAdapter adapter = adapterMap.get(sourceType);

        if (adapter == null) {
            throw new IllegalArgumentException(
                "AdapterRegistry: No adapter registered for SourceType: " + sourceType +
                ". Registered types are: " + adapterMap.keySet()
            );
        }

        return adapter;
    }

    /**
     * Checks whether an adapter is registered for the given SourceType.
     * Useful for validation before calling getAdapter().
     *
     * @param sourceType The source type to check
     * @return true if an adapter exists, false otherwise
     */
    public boolean hasAdapter(SourceType sourceType) {
        return sourceType != null && adapterMap.containsKey(sourceType);
    }

    /**
     * Returns the total number of adapters currently registered.
     * Useful for startup logging/verification.
     *
     * @return count of registered adapters
     */
    public int getRegisteredCount() {
        return adapterMap.size();
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "AdapterRegistry{registeredSources=" + adapterMap.keySet() + "}";
    }
}