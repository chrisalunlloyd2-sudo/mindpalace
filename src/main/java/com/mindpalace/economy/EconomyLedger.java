package com.mindpalace.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared, append-only accounting journal for the local economy. */
public final class EconomyLedger {
    public enum Type { GENESIS, CREDIT, DEBIT, TRANSFER }

    public static final class Entry {
        public final long id;
        public final Instant timestamp;
        public final Type type;
        public final String from;
        public final String to;
        public final double amount;
        public final String reason;
        public final String reference;

        private Entry(long id, Type type, String from, String to, long cents,
                      String reason, String reference) {
            this.id = id;
            this.timestamp = Instant.now();
            this.type = type;
            this.from = from;
            this.to = to;
            this.amount = cents / 100.0;
            this.reason = reason;
            this.reference = reference;
        }
    }

    private static final String ISSUANCE = "system:issuance";
    private static final String SINK = "system:sink";
    private final Map<String, Long> balances = new LinkedHashMap<>();
    private final List<Entry> entries = new ArrayList<>();

    public EconomyLedger() {
        balances.put(ISSUANCE, Long.MAX_VALUE);
        balances.put(SINK, 0L);
    }

    synchronized void openAccount(String account, double initial, String reason) {
        requireAccount(account);
        long cents = toCents(initial);
        if (cents < 0) throw new IllegalArgumentException("Initial balance must be finite and non-negative");
        if (balances.containsKey(account)) throw new IllegalArgumentException("Account already exists: " + account);
        long issued = balances.get(ISSUANCE);
        if (issued < cents) throw new IllegalArgumentException("Initial balance exceeds local issuance limit");
        balances.put(ISSUANCE, issued - cents);
        balances.put(account, cents);
        entries.add(new Entry(entries.size() + 1L, Type.GENESIS, ISSUANCE, account,
            cents, reason, null));
    }

    synchronized boolean credit(String account, double amount, String reason, String reference) {
        long cents = toCents(amount);
        return cents > 0 && transferCents(ISSUANCE, account, cents, Type.CREDIT, reason, reference);
    }

    synchronized boolean debit(String account, double amount, String reason, String reference) {
        long cents = toCents(amount);
        return cents > 0 && transferCents(account, SINK, cents, Type.DEBIT, reason, reference);
    }

    synchronized boolean transfer(String from, String to, double amount, String reason, String reference) {
        long cents = toCents(amount);
        return cents > 0 && transferCents(from, to, cents, Type.TRANSFER, reason, reference);
    }

    private boolean transferCents(String from, String to, long cents, Type type,
                                   String reason, String reference) {
        requireAccount(from);
        requireAccount(to);
        if (from.equals(to) || cents <= 0) return false;
        long sourceBalance = balances.getOrDefault(from, 0L);
        long destinationBalance = balances.getOrDefault(to, 0L);
        if (sourceBalance < cents || destinationBalance > Long.MAX_VALUE - cents) return false;
        balances.put(from, sourceBalance - cents);
        balances.put(to, destinationBalance + cents);
        entries.add(new Entry(entries.size() + 1L, type, from, to, cents,
            reason == null ? "" : reason, reference));
        return true;
    }

    public synchronized double balance(String account) {
        return balances.getOrDefault(account, 0L) / 100.0;
    }

    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized List<Entry> entriesFor(String account) {
        List<Entry> matching = new ArrayList<>();
        for (Entry entry : entries) {
            if (account.equals(entry.from) || account.equals(entry.to)) matching.add(entry);
        }
        return Collections.unmodifiableList(matching);
    }

    public synchronized int transactionCount() {
        return entries.size();
    }

    static long toCents(double amount) {
        if (!Double.isFinite(amount) || amount < 0) return -1;
        try {
            return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    private static void requireAccount(String account) {
        if (account == null || account.trim().isEmpty()) {
            throw new IllegalArgumentException("Account name must not be blank");
        }
    }
}
