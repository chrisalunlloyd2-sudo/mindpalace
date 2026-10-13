package com.mindpalace.economy;

import java.util.LinkedHashMap;
import java.util.Map;

/** Holds funded job bounties until completion or cancellation. */
public final class Escrow {
    private final EconomyLedger ledger;
    private final Map<Long, Double> holds = new LinkedHashMap<>();

    Escrow(EconomyLedger ledger) {
        this.ledger = ledger;
    }

    synchronized boolean reserve(long jobId, double amount) {
        if (jobId <= 0 || holds.containsKey(jobId)) return false;
        long cents = EconomyLedger.toCents(amount);
        if (cents <= 0) return false;
        String account = account(jobId);
        if (!ledger.transfer(Treasury.ACCOUNT, account, cents / 100.0,
                "Job bounty escrow", "job:" + jobId)) return false;
        holds.put(jobId, cents / 100.0);
        return true;
    }

    synchronized boolean release(long jobId, Wallet recipient) {
        Double amount = holds.get(jobId);
        if (amount == null || recipient == null) return false;
        if (!ledger.transfer(account(jobId), recipient.accountId(), amount,
                "Job bounty payout", "job:" + jobId)) return false;
        holds.remove(jobId);
        return true;
    }

    synchronized boolean refund(long jobId) {
        Double amount = holds.get(jobId);
        if (amount == null) return false;
        if (!ledger.transfer(account(jobId), Treasury.ACCOUNT, amount,
                "Cancelled job bounty refund", "job:" + jobId)) return false;
        holds.remove(jobId);
        return true;
    }

    public synchronized double held(long jobId) {
        return holds.getOrDefault(jobId, 0.0);
    }

    public synchronized double totalHeld() {
        double total = 0;
        for (double amount : holds.values()) total += amount;
        return total;
    }

    private static String account(long jobId) {
        return "escrow:job:" + jobId;
    }
}
