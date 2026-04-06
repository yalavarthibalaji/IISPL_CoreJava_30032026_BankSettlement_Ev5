package com.iispl.banksettlement.threading;

import com.iispl.banksettlement.dao.AccountDao;
import com.iispl.banksettlement.dao.CreditTransactionDao;
import com.iispl.banksettlement.dao.DebitTransactionDao;
import com.iispl.banksettlement.dao.InterBankTransactionDao;
import com.iispl.banksettlement.dao.IncomingTransactionDao;
import com.iispl.banksettlement.dao.ReversalTransactionDao;
import com.iispl.banksettlement.dao.impl.AccountDaoImpl;
import com.iispl.banksettlement.dao.impl.CreditTransactionDaoImpl;
import com.iispl.banksettlement.dao.impl.DebitTransactionDaoImpl;
import com.iispl.banksettlement.dao.impl.InterBankTransactionDaoImpl;
import com.iispl.banksettlement.dao.impl.IncomingTransactionDaoImpl;
import com.iispl.banksettlement.dao.impl.ReversalTransactionDaoImpl;
import com.iispl.banksettlement.entity.Account;
import com.iispl.banksettlement.entity.CreditTransaction;
import com.iispl.banksettlement.entity.DebitTransaction;
import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.entity.InterBankTransaction;
import com.iispl.banksettlement.entity.ReversalTransaction;
import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.enums.TransactionStatus;
import com.iispl.banksettlement.enums.TransactionType;
import com.iispl.connectionpool.ConnectionPool;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.BlockingQueue;

/**
 * TransactionDispatcher — Runnable that drains the BlockingQueue and
 * dispatches each IncomingTransaction to the correct Transaction subclass.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * WHAT THIS CLASS DOES (step by step):
 * ─────────────────────────────────────────────────────────────────────────
 *
 *  STEP 1 — Take from BlockingQueue
 *      blockingQueue.take() — blocks until a QUEUED IncomingTransaction arrives.
 *
 *  STEP 2 — Parse normalizedPayload (JSON)
 *      Extract: debitAccount, creditAccount, amount, currency,
 *               valueDate, txnType, originalTxnRef, reversalReason,
 *               senderIFSC (for INTRABANK correspondentBankCode)
 *
 *  STEP 3 — Look up account IDs from account table
 *      AccountDao.findByAccountNumber(debitAccount)  → debitAccountId (Long)
 *      AccountDao.findByAccountNumber(creditAccount) → creditAccountId (Long)
 *      If either account is not found → log error, mark FAILED, skip.
 *      UPI uses VPA handles (not real account numbers) — account lookup skipped,
 *      IDs set to 0L as placeholder.
 *
 *  STEP 4 — Route by txnType and build the correct subclass object
 *      CREDIT    → new CreditTransaction(...)    → CreditTransactionDao.save()
 *      DEBIT     → new DebitTransaction(...)     → DebitTransactionDao.save()
 *      REVERSAL  → new ReversalTransaction(...)  → ReversalTransactionDao.save()
 *      INTRABANK → new InterBankTransaction(...) → InterBankTransactionDao.save()
 *      OTHER     → log and mark FAILED
 *
 *  STEP 5 — Update incoming_transaction status to PROCESSED
 *      IncomingTransactionDao.updateStatus(id, PROCESSED)
 *      On any error → mark as FAILED instead.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * NOTE ON TABLE DESIGN:
 *   The four transaction tables (credit_transaction, debit_transaction,
 *   reversal_transaction, interbank_transaction) do NOT have an
 *   incoming_txn_id column. The link to incoming_transaction is maintained
 *   via reference_number, which stores the sourceRef from IncomingTransaction.
 * ─────────────────────────────────────────────────────────────────────────
 */
public class TransactionDispatcher implements Runnable {

    private final BlockingQueue<IncomingTransaction> blockingQueue;

    private final CreditTransactionDao    creditTxnDao;
    private final DebitTransactionDao     debitTxnDao;
    private final ReversalTransactionDao  reversalTxnDao;
    private final InterBankTransactionDao interbankTxnDao;
    private final IncomingTransactionDao  incomingTxnDao;
    private final AccountDao              accountDao;

    public TransactionDispatcher(BlockingQueue<IncomingTransaction> blockingQueue) {
        this.blockingQueue   = blockingQueue;
        this.creditTxnDao    = new CreditTransactionDaoImpl();
        this.debitTxnDao     = new DebitTransactionDaoImpl();
        this.reversalTxnDao  = new ReversalTransactionDaoImpl();
        this.interbankTxnDao = new InterBankTransactionDaoImpl();
        this.incomingTxnDao  = new IncomingTransactionDaoImpl();
        this.accountDao      = new AccountDaoImpl();
    }

    @Override
    public void run() {

        System.out.println("[TransactionDispatcher] Started. Waiting for transactions on queue...");
        System.out.println("[TransactionDispatcher] Thread: " + Thread.currentThread().getName());

        while (!Thread.currentThread().isInterrupted()) {

            IncomingTransaction incoming = null;

            try {
                // STEP 1: block until something arrives
                incoming = blockingQueue.take();

                // Shutdown sentinel
                if ("SHUTDOWN".equals(incoming.getSourceRef())) {
                    System.out.println("[TransactionDispatcher] Shutdown signal received. Stopping.");
                    break;
                }

                System.out.println("\n[TransactionDispatcher] Dispatching: "
                        + incoming.getSourceRef()
                        + " | type: "   + incoming.getTxnType()
                        + " | amount: " + incoming.getAmount());

                dispatch(incoming);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("[TransactionDispatcher] Interrupted. Stopping.");
                break;

            } catch (Exception e) {
                System.out.println("[TransactionDispatcher] ERROR dispatching: "
                        + (incoming != null ? incoming.getSourceRef() : "unknown")
                        + " | " + e.getMessage());
                e.printStackTrace();

                if (incoming != null && incoming.getIncomingTxnId() != null) {
                    try {
                        incomingTxnDao.updateStatus(incoming.getIncomingTxnId(), ProcessingStatus.FAILED);
                    } catch (Exception ex) {
                        System.out.println("[TransactionDispatcher] Could not mark FAILED: " + ex.getMessage());
                    }
                }
            }
        }

        System.out.println("[TransactionDispatcher] Loop ended. Closing connection pool.");
        ConnectionPool.shutdown();
    }

    // -----------------------------------------------------------------------
    // dispatch — handles one IncomingTransaction
    // -----------------------------------------------------------------------

    private void dispatch(IncomingTransaction incoming) {

        String normalizedPayload = incoming.getNormalizedPayload();
        TransactionType txnType  = incoming.getTxnType();

        // STEP 2: Parse normalizedPayload fields
        String debitAccountNum  = extractJsonField(normalizedPayload, "debitAccount");
        String creditAccountNum = extractJsonField(normalizedPayload, "creditAccount");
        String currency         = nullSafe(extractJsonField(normalizedPayload, "currency"));
        String originalTxnRef   = nullSafe(extractJsonField(normalizedPayload, "originalTxnRef"));
        String reversalReason   = nullSafe(extractJsonField(normalizedPayload, "reversalReason"));
        // senderIFSC used as correspondentBankCode for INTRABANK
        String senderIFSC       = nullSafe(extractJsonField(normalizedPayload, "senderIFSC"));

        if (debitAccountNum == null || creditAccountNum == null) {
            System.out.println("[TransactionDispatcher] SKIPPING — missing debitAccount or "
                    + "creditAccount in normalizedPayload for: " + incoming.getSourceRef());
            incomingTxnDao.updateStatus(incoming.getIncomingTxnId(), ProcessingStatus.FAILED);
            return;
        }

        // STEP 3: Look up account IDs
        // UPI uses VPA handles — no real rows in account table for them
        boolean isUpi = normalizedPayload.contains("\"accountValidationSkipped\":true");

        Long debitAccountId;
        Long creditAccountId;

        if (isUpi) {
            // UPI VPA addresses don't have account table rows — use 0L as placeholder
            debitAccountId  = 0L;
            creditAccountId = 0L;
            System.out.println("[TransactionDispatcher] UPI transaction — skipping account lookup for VPA addresses.");
        } else {
            Account debitAccount  = accountDao.findByAccountNumber(debitAccountNum);
            Account creditAccount = accountDao.findByAccountNumber(creditAccountNum);

            if (debitAccount == null) {
                System.out.println("[TransactionDispatcher] FAILED — debitAccount not found in DB: "
                        + debitAccountNum + " | sourceRef: " + incoming.getSourceRef());
                incomingTxnDao.updateStatus(incoming.getIncomingTxnId(), ProcessingStatus.FAILED);
                return;
            }
            if (creditAccount == null) {
                System.out.println("[TransactionDispatcher] FAILED — creditAccount not found in DB: "
                        + creditAccountNum + " | sourceRef: " + incoming.getSourceRef());
                incomingTxnDao.updateStatus(incoming.getIncomingTxnId(), ProcessingStatus.FAILED);
                return;
            }

            debitAccountId  = debitAccount.getId();
            creditAccountId = creditAccount.getId();
        }

        BigDecimal amount    = incoming.getAmount();
        LocalDate  valueDate = incoming.getValueDate();
        String     sourceRef = incoming.getSourceRef();

        // STEP 4: Build the correct subclass and save to its table
        switch (txnType) {

            // ----------------------------------------------------------------
            // CREDIT — money comes into creditAccount
            // ----------------------------------------------------------------
            case CREDIT: {
                CreditTransaction credit = new CreditTransaction(
                    debitAccountId,
                    creditAccountId,
                    creditAccountNum,
                    amount,
                    currency.isEmpty() ? "INR" : currency,
                    valueDate,
                    sourceRef          // sourceRef is stored as reference_number
                );
                credit.setStatus(TransactionStatus.INITIATED);
                credit.setCreatedBy("DISPATCHER");

                creditTxnDao.save(credit);

                System.out.println("[TransactionDispatcher] CREDIT saved | txn_id: "
                        + credit.getTxnId()
                        + " | creditAccount: " + creditAccountNum);
                break;
            }

            // ----------------------------------------------------------------
            // DEBIT — money goes out of debitAccount
            // ----------------------------------------------------------------
            case DEBIT: {
                DebitTransaction debit = new DebitTransaction(
                    debitAccountId,
                    debitAccountNum,
                    creditAccountId,
                    amount,
                    currency.isEmpty() ? "INR" : currency,
                    valueDate,
                    sourceRef
                );
                debit.setStatus(TransactionStatus.INITIATED);
                debit.setCreatedBy("DISPATCHER");

                debitTxnDao.save(debit);

                System.out.println("[TransactionDispatcher] DEBIT saved | txn_id: "
                        + debit.getTxnId()
                        + " | debitAccount: " + debitAccountNum);
                break;
            }

            // ----------------------------------------------------------------
            // REVERSAL — undo of a previous transaction
            // ----------------------------------------------------------------
            case REVERSAL: {
                ReversalTransaction reversal = new ReversalTransaction(
                    debitAccountId,
                    creditAccountId,
                    amount,
                    currency.isEmpty() ? "INR" : currency,
                    valueDate,
                    sourceRef,
                    originalTxnRef,
                    reversalReason.isEmpty() ? "UNSPECIFIED" : reversalReason
                );
                reversal.setStatus(TransactionStatus.INITIATED);
                reversal.setCreatedBy("DISPATCHER");

                reversalTxnDao.save(reversal);

                System.out.println("[TransactionDispatcher] REVERSAL saved | txn_id: "
                        + reversal.getTxnId()
                        + " | originalRef: " + originalTxnRef);
                break;
            }

            // ----------------------------------------------------------------
            // INTRABANK — inter-bank bilateral settlement via RTGS
            // ----------------------------------------------------------------
            case INTRABANK: {
                // correspondentBankCode = senderIFSC for RTGS interbank.
                // Fall back to fromBank name if senderIFSC is blank.
                String correspondentCode = senderIFSC.isEmpty()
                        ? nullSafe(extractJsonField(normalizedPayload, "fromBank"))
                        : senderIFSC;

                InterBankTransaction interbank = new InterBankTransaction(
                    debitAccountId,
                    creditAccountId,
                    correspondentCode,
                    amount,
                    currency.isEmpty() ? "INR" : currency,
                    valueDate,
                    sourceRef
                );
                interbank.setStatus(TransactionStatus.INITIATED);
                interbank.setCreatedBy("DISPATCHER");

                interbankTxnDao.save(interbank);

                System.out.println("[TransactionDispatcher] INTRABANK saved | txn_id: "
                        + interbank.getTxnId()
                        + " | correspondent: " + correspondentCode);
                break;
            }

            // ----------------------------------------------------------------
            // Unsupported types (FEE, SWAP etc.) — not implemented yet
            // ----------------------------------------------------------------
            default: {
                System.out.println("[TransactionDispatcher] SKIPPING unsupported txnType: "
                        + txnType + " | sourceRef: " + sourceRef);
                incomingTxnDao.updateStatus(incoming.getIncomingTxnId(), ProcessingStatus.FAILED);
                return;
            }
        }

        // STEP 5: Mark incoming_transaction as PROCESSED
        incomingTxnDao.updateStatus(incoming.getIncomingTxnId(), ProcessingStatus.PROCESSED);
        System.out.println("[TransactionDispatcher] Marked PROCESSED | incoming_txn_id: "
                + incoming.getIncomingTxnId());
    }

    // -----------------------------------------------------------------------
    // extractJsonField — pure Core Java JSON field extractor
    // -----------------------------------------------------------------------

    private String extractJsonField(String json, String fieldName) {
        if (json == null || fieldName == null) return null;

        String searchKey = "\"" + fieldName + "\":";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) return null;

        int valueStart = keyIndex + searchKey.length();
        while (valueStart < json.length() && json.charAt(valueStart) == ' ') {
            valueStart++;
        }

        boolean isQuoted = (valueStart < json.length() && json.charAt(valueStart) == '"');

        if (isQuoted) {
            int openQuote  = valueStart;
            int closeQuote = json.indexOf('"', openQuote + 1);
            if (closeQuote == -1) return null;
            return json.substring(openQuote + 1, closeQuote);
        } else {
            int endIndex = json.indexOf(',', valueStart);
            if (endIndex == -1) endIndex = json.indexOf('}', valueStart);
            if (endIndex == -1) endIndex = json.length();
            return json.substring(valueStart, endIndex).trim();
        }
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
