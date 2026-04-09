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
 * TransactionDispatcher — Runnable that drains the BlockingQueue and dispatches
 * each IncomingTransaction to the correct Transaction subclass.
 *
 * UPDATED: Now also sets fromBank, toBank, and incomingTxnId on every
 * transaction subclass before saving to DB.
 *
 * HOW fromBank / toBank are set:
 *   They are parsed from the normalizedPayload JSON of each IncomingTransaction.
 *   All adapters (CBS, RTGS, NEFT, UPI, Fintech) already put "fromBank" and
 *   "toBank" as keys inside normalizedPayload. The dispatcher just reads them.
 *
 * HOW incomingTxnId is set:
 *   incoming.getIncomingTxnId() gives us the DB primary key of the
 *   IncomingTransaction row. We copy it to each transaction subclass as a FK.
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

        System.out.println("[TransactionDispatcher] Started. Waiting for transactions...");
        System.out.println("[TransactionDispatcher] Thread: " + Thread.currentThread().getName());

        while (!Thread.currentThread().isInterrupted()) {

            IncomingTransaction incoming = null;

            try {
                incoming = blockingQueue.take();

                // Shutdown sentinel
                if ("SHUTDOWN".equals(incoming.getSourceRef())) {
                    System.out.println("[TransactionDispatcher] Shutdown signal received. Stopping.");
                    break;
                }

                System.out.println("\n[TransactionDispatcher] Dispatching: " + incoming.getSourceRef()
                        + " | type: " + incoming.getTxnType()
                        + " | amount: " + incoming.getAmount()
                        + " | fromBank: " + incoming.getFromBank()
                        + " | toBank: " + incoming.getToBank());

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

        // Parse all fields from normalizedPayload
        String debitAccountNum  = extractJsonField(normalizedPayload, "debitAccount");
        String creditAccountNum = extractJsonField(normalizedPayload, "creditAccount");
        String currency         = nullSafe(extractJsonField(normalizedPayload, "currency"));
        String originalTxnRef   = nullSafe(extractJsonField(normalizedPayload, "originalTxnRef"));
        String reversalReason   = nullSafe(extractJsonField(normalizedPayload, "reversalReason"));
        String senderIFSC       = nullSafe(extractJsonField(normalizedPayload, "senderIFSC"));

        // fromBank and toBank — parse from normalizedPayload
        // These are stored both in IncomingTransaction fields AND in normalizedPayload JSON
        String fromBank = nullSafe(extractJsonField(normalizedPayload, "fromBank"));
        String toBank   = nullSafe(extractJsonField(normalizedPayload, "toBank"));

        // Also try to get from IncomingTransaction entity fields as fallback
        if (fromBank.isEmpty() && incoming.getFromBank() != null) {
            fromBank = incoming.getFromBank();
        }
        if (toBank.isEmpty() && incoming.getToBank() != null) {
            toBank = incoming.getToBank();
        }

        if (debitAccountNum == null || creditAccountNum == null) {
            System.out.println("[TransactionDispatcher] SKIPPING — missing debitAccount or creditAccount for: "
                    + incoming.getSourceRef());
            incomingTxnDao.updateStatus(incoming.getIncomingTxnId(), ProcessingStatus.FAILED);
            return;
        }

        // UPI uses VPA handles — skip account table lookup
        boolean isUpi = normalizedPayload.contains("\"accountValidationSkipped\":true");

        Long debitAccountId;
        Long creditAccountId;

        if (isUpi) {
            debitAccountId  = 0L;
            creditAccountId = 0L;
            System.out.println("[TransactionDispatcher] UPI — skipping account lookup for VPA addresses.");
        } else {
            Account debitAccount  = accountDao.findByAccountNumber(debitAccountNum);
            Account creditAccount = accountDao.findByAccountNumber(creditAccountNum);

            if (debitAccount == null) {
                System.out.println("[TransactionDispatcher] FAILED — debit account not found: "
                        + debitAccountNum + " | ref: " + incoming.getSourceRef());
                incomingTxnDao.updateStatus(incoming.getIncomingTxnId(), ProcessingStatus.FAILED);
                return;
            }
            if (creditAccount == null) {
                System.out.println("[TransactionDispatcher] FAILED — credit account not found: "
                        + creditAccountNum + " | ref: " + incoming.getSourceRef());
                incomingTxnDao.updateStatus(incoming.getIncomingTxnId(), ProcessingStatus.FAILED);
                return;
            }

            debitAccountId  = debitAccount.getId();
            creditAccountId = creditAccount.getId();
        }

        BigDecimal amount    = incoming.getAmount();
        LocalDate  valueDate = incoming.getValueDate();
        String     sourceRef = incoming.getSourceRef();

        // incomingTxnId — FK linking back to the incoming_transaction row
        Long incomingTxnId = incoming.getIncomingTxnId();

        // Build and save the correct transaction subclass
        switch (txnType) {

            case CREDIT: {
                CreditTransaction credit = new CreditTransaction(
                        debitAccountId, creditAccountId, creditAccountNum,
                        amount, currency.isEmpty() ? "INR" : currency, valueDate, sourceRef);
                credit.setStatus(TransactionStatus.INITIATED);
                credit.setCreatedBy("DISPATCHER");
                // Set the new fields
                credit.setFromBank(fromBank);
                credit.setToBank(toBank);
                credit.setIncomingTxnId(incomingTxnId);

                creditTxnDao.save(credit);

                System.out.println("[TransactionDispatcher] CREDIT saved | txn_id: " + credit.getTxnId()
                        + " | creditAccount: " + creditAccountNum
                        + " | fromBank: " + fromBank + " | toBank: " + toBank);
                break;
            }

            case DEBIT: {
                DebitTransaction debit = new DebitTransaction(
                        debitAccountId, debitAccountNum, creditAccountId,
                        amount, currency.isEmpty() ? "INR" : currency, valueDate, sourceRef);
                debit.setStatus(TransactionStatus.INITIATED);
                debit.setCreatedBy("DISPATCHER");
                debit.setFromBank(fromBank);
                debit.setToBank(toBank);
                debit.setIncomingTxnId(incomingTxnId);

                debitTxnDao.save(debit);

                System.out.println("[TransactionDispatcher] DEBIT saved | txn_id: " + debit.getTxnId()
                        + " | debitAccount: " + debitAccountNum
                        + " | fromBank: " + fromBank + " | toBank: " + toBank);
                break;
            }

            case REVERSAL: {
                ReversalTransaction reversal = new ReversalTransaction(
                        debitAccountId, creditAccountId, amount,
                        currency.isEmpty() ? "INR" : currency, valueDate, sourceRef,
                        originalTxnRef, reversalReason.isEmpty() ? "UNSPECIFIED" : reversalReason);
                reversal.setStatus(TransactionStatus.INITIATED);
                reversal.setCreatedBy("DISPATCHER");
                reversal.setFromBank(fromBank);
                reversal.setToBank(toBank);
                reversal.setIncomingTxnId(incomingTxnId);

                reversalTxnDao.save(reversal);

                System.out.println("[TransactionDispatcher] REVERSAL saved | txn_id: " + reversal.getTxnId()
                        + " | originalRef: " + originalTxnRef
                        + " | fromBank: " + fromBank + " | toBank: " + toBank);
                break;
            }

            case INTRABANK: {
                // correspondentBankCode = senderIFSC, fallback to fromBank name
                String correspondentCode = senderIFSC.isEmpty() ? fromBank : senderIFSC;

                InterBankTransaction interbank = new InterBankTransaction(
                        debitAccountId, creditAccountId, correspondentCode,
                        amount, currency.isEmpty() ? "INR" : currency, valueDate, sourceRef);
                interbank.setStatus(TransactionStatus.INITIATED);
                interbank.setCreatedBy("DISPATCHER");
                interbank.setFromBank(fromBank);
                interbank.setToBank(toBank);
                interbank.setIncomingTxnId(incomingTxnId);

                interbankTxnDao.save(interbank);

                System.out.println("[TransactionDispatcher] INTRABANK saved | txn_id: " + interbank.getTxnId()
                        + " | correspondent: " + correspondentCode
                        + " | fromBank: " + fromBank + " | toBank: " + toBank);
                break;
            }

            default: {
                System.out.println("[TransactionDispatcher] SKIPPING unsupported txnType: "
                        + txnType + " | ref: " + sourceRef);
                incomingTxnDao.updateStatus(incoming.getIncomingTxnId(), ProcessingStatus.FAILED);
                return;
            }
        }

        // Mark IncomingTransaction as PROCESSED
        incomingTxnDao.updateStatus(incoming.getIncomingTxnId(), ProcessingStatus.PROCESSED);
        System.out.println("[TransactionDispatcher] Marked PROCESSED | incoming_txn_id: "
                + incoming.getIncomingTxnId());
    }

    // -----------------------------------------------------------------------
    // Pure Java JSON field extractor (no external libraries needed)
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