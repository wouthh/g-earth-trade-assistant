package io.github.wouthh.tradeassistant.domain;

import java.util.HashSet;
import java.util.Set;

/** Contract-independent bounded policy. There is deliberately no live Origins purchase binding. */
public final class PurchasePhase {
    public static final String PRODUCT = "CF_50_goldbar";
    public static final String UNAVAILABLE =
            "Live purchasing unavailable: unit cost, quantity, delivery acknowledgement and balance attribution are unverified.";

    public record Budget(
            int count,
            long unitCost,
            long available,
            long confirmedProceeds,
            long maxSpend,
            boolean useExistingBalance) {
        public Budget {
            if (count < 1
                    || count > 1000
                    || unitCost <= 0
                    || available < 0
                    || confirmedProceeds < 0
                    || maxSpend < 0) throw new IllegalArgumentException("Invalid purchase budget");
            long total = Math.multiplyExact(count, unitCost);
            if (total > maxSpend
                    || total > available
                    || (!useExistingBalance && total > confirmedProceeds))
                throw new IllegalArgumentException("Insufficient confirmed funds or budget");
        }

        public long total() {
            return Math.multiplyExact(count, unitCost);
        }
    }

    public interface VerifiedContract {
        /**
         * Implement only after separately verified catalogue, success and delivery evidence exists.
         */
        boolean sendOne(String product);
    }

    private final VerifiedContract contract;
    private final Set<String> receipts = new HashSet<>();
    private Budget budget;
    private int sent, received;
    private long spend, balance;
    private boolean pending, stopped, unknown;

    public PurchasePhase(VerifiedContract contract) {
        this.contract = contract;
    }

    public void start(Budget plan, boolean confirmed) {
        if (contract == null) throw new IllegalStateException(UNAVAILABLE);
        if (budget != null) throw new IllegalStateException("Purchase phase already started");
        if (!confirmed)
            throw new IllegalArgumentException(
                    "Confirm the disclosed product/count/cost/balance/budget first");
        budget = plan;
        balance = plan.available();
    }

    public void next() {
        if (budget == null || stopped || unknown || pending || sent == budget.count()) return;
        pending = true;
        sent++;
        try {
            if (!contract.sendOne(PRODUCT)) timeout();
        } catch (RuntimeException e) {
            timeout();
        }
    }

    public void delivered(String receipt, String product, int units, long reconciledBalance) {
        if (receipt == null || receipt.isBlank() || receipt.length() > 128) {
            timeout();
            return;
        }
        if (receipts.contains(receipt)) return;
        if (!pending
                || unknown
                || !PRODUCT.equals(product)
                || units != 1
                || balance - budget.unitCost() != reconciledBalance) {
            timeout();
            return;
        }
        receipts.add(receipt);
        pending = false;
        received++;
        spend = Math.addExact(spend, budget.unitCost());
        balance = reconciledBalance;
    }

    public void stop() {
        stopped = true;
    }

    public void timeout() {
        unknown = true;
        stopped = true;
    }

    public int sent() {
        return sent;
    }

    public int received() {
        return received;
    }

    public long spend() {
        return spend;
    }

    public boolean unknown() {
        return unknown;
    }
}
