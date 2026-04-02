package com.iispl.banksettlement.threading;

import com.iispl.banksettlement.adapter.TransactionAdapter;
import com.iispl.banksettlement.dao.AccountDao;
import com.iispl.banksettlement.dao.CustomerDao;
import com.iispl.banksettlement.dao.IncomingTransactionDao;
import com.iispl.banksettlement.entity.Account;
import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.registry.AdapterRegistry;

import java.util.concurrent.BlockingQueue;

/**
 * IngestionWorker — One Runnable per raw payload.
 *
 * FULL 7-STEP FLOW (updated):
 *   1. Get the correct adapter from AdapterRegistry
 *   2. Call adapt() to get a canonical IncomingTransaction
 *   3. Check for duplicate (same sourceRef already in DB → skip)
 *   4. Validate debit account — must exist and be ACTIVE
 *   5. Validate credit account — must exist and be ACTIVE
 *   6. Validate customers linked to both accounts — KYC must be VERIFIED
 *   7. If validation passes → save to DB, set QUEUED, put on BlockingQueue
 *      If validation fails → save to DB with status FAILED, skip queue
 *
 * WHY Runnable and not Callable:
 *   No return value needed — fire-and-forget ingestion.
 */
public class IngestionWorker implements Runnable {

    private final SourceType sourceType;
    private final String rawPayload;
    private final AdapterRegistry adapterRegistry;
    private final BlockingQueue<IncomingTransaction> blockingQueue;
    private final IncomingTransactionDao incomingTransactionDao;
    private final AccountDao accountDao;
    private final CustomerDao customerDao;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public IngestionWorker(SourceType sourceType,
                           String rawPayload,
                           AdapterRegistry adapterRegistry,
                           BlockingQueue<IncomingTransaction> blockingQueue,
                           IncomingTransactionDao incomingTransactionDao,
                           AccountDao accountDao,
                           CustomerDao customerDao) {

        this.sourceType             = sourceType;
        this.rawPayload             = rawPayload;
        this.adapterRegistry        = adapterRegistry;
        this.blockingQueue          = blockingQueue;
        this.incomingTransactionDao = incomingTransactionDao;
        this.accountDao             = accountDao;
        this.customerDao            = customerDao;
    }

    // -----------------------------------------------------------------------
    // run() — executed by the ThreadPoolExecutor
    // -----------------------------------------------------------------------

    @Override
    public void run() {

        System.out.println("[IngestionWorker] Thread started for source: "
                + sourceType + " | Thread: " + Thread.currentThread().getName());

        try {

            // ----------------------------------------------------------------
            // STEP 1: Get adapter for this source type
            // ----------------------------------------------------------------
            TransactionAdapter adapter = adapterRegistry.getAdapter(sourceType);

            // ----------------------------------------------------------------
            // STEP 2: Convert raw payload → canonical IncomingTransaction
            // ----------------------------------------------------------------
            IncomingTransaction txn = adapter.adapt(rawPayload);

            System.out.println("[IngestionWorker] Adapted txn: "
                    + txn.getSourceRef()
                    + " | Amount: " + txn.getAmount() + " " + txn.getCurrency()
                    + " | Debit: " + txn.getDebitAccountNumber()
                    + " | Credit: " + txn.getCreditAccountNumber());

            // ----------------------------------------------------------------
            // STEP 3: Duplicate check — same sourceRef already in DB?
            // ----------------------------------------------------------------
            if (incomingTransactionDao.existsBySourceRef(txn.getSourceRef())) {
                System.out.println("[IngestionWorker] DUPLICATE detected — skipping sourceRef: "
                        + txn.getSourceRef());
                return;
            }

            // ----------------------------------------------------------------
            // STEP 4 & 5 & 6: Account and Customer Validation
            // ----------------------------------------------------------------
            String validationError = validateAccountsAndCustomers(txn);

            if (validationError != null) {

                // Validation failed — save to DB with FAILED status, skip queue
                txn.setProcessingStatus(ProcessingStatus.FAILED);
                txn.setFailureReason(validationError);
                incomingTransactionDao.save(txn);

                System.out.println("[IngestionWorker] VALIDATION FAILED for sourceRef: "
                        + txn.getSourceRef()
                        + " | Reason: " + validationError
                        + " | Saved with status FAILED (DB id: " + txn.getIncomingTxnId() + ")");
                return;
            }

            // ----------------------------------------------------------------
            // STEP 7: Validation passed — save to DB with QUEUED status
            // ----------------------------------------------------------------
            incomingTransactionDao.save(txn);
            System.out.println("[IngestionWorker] Saved to DB — id: " + txn.getIncomingTxnId());

            // Update status to QUEUED
            txn.setProcessingStatus(ProcessingStatus.QUEUED);
            incomingTransactionDao.updateStatus(txn.getIncomingTxnId(), ProcessingStatus.QUEUED);

            // Put into BlockingQueue for SettlementProcessor
            blockingQueue.put(txn);

            System.out.println("[IngestionWorker] QUEUED txn: "
                    + txn.getSourceRef()
                    + " | Queue size now: " + blockingQueue.size());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[IngestionWorker] Thread interrupted for " + sourceType);

        } catch (IllegalArgumentException e) {
            // Comment/header lines in files throw this — silently skip
            System.out.println("[IngestionWorker] Skipping line from "
                    + sourceType + ": " + e.getMessage());

        } catch (Exception e) {
            System.out.println("[IngestionWorker] ERROR processing payload from "
                    + sourceType + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // -----------------------------------------------------------------------
    // Private — Validate debit account, credit account, and their customers
    //
    // Returns: null if everything is valid
    //          error message String if anything fails
    // -----------------------------------------------------------------------

    private String validateAccountsAndCustomers(IncomingTransaction txn) {

        String debitAccNum  = txn.getDebitAccountNumber();
        String creditAccNum = txn.getCreditAccountNumber();

        // ---- STEP 4: Check debit account ----

        // Does debit account exist and is it ACTIVE?
        if (!accountDao.isAccountActiveByNumber(debitAccNum)) {
            return "Debit account [" + debitAccNum + "] not found or not ACTIVE in database";
        }

        // Get full debit account to find its customer
        Account debitAccount = accountDao.findByAccountNumber(debitAccNum);

        // ---- STEP 5: Check credit account ----

        // Does credit account exist and is it ACTIVE?
        if (!accountDao.isAccountActiveByNumber(creditAccNum)) {
            return "Credit account [" + creditAccNum + "] not found or not ACTIVE in database";
        }

        // Get full credit account to find its customer
        Account creditAccount = accountDao.findByAccountNumber(creditAccNum);

        // ---- STEP 6: Check customers linked to both accounts ----

        // Validate customer of debit account
        if (debitAccount.getCustomerId() != null) {
            if (!customerDao.isCustomerKycVerified(debitAccount.getCustomerId())) {
                return "Customer [id=" + debitAccount.getCustomerId() + "] linked to debit account ["
                       + debitAccNum + "] is not KYC VERIFIED";
            }
        }

        // Validate customer of credit account
        if (creditAccount.getCustomerId() != null) {
            if (!customerDao.isCustomerKycVerified(creditAccount.getCustomerId())) {
                return "Customer [id=" + creditAccount.getCustomerId() + "] linked to credit account ["
                       + creditAccNum + "] is not KYC VERIFIED";
            }
        }

        // All checks passed
        return null;
    }
}