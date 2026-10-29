package routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Implements the paper's sufficient Corollary 2 admission condition and Eq. (20)
 * remaining-resource expression for token-bucket arrival envelopes.
 *
 * Units: time is ms, bandwidth is Mbps, and traffic volume is decimal KB. Thus
 * c_h t = bandwidthMbps * 0.125 * timeMs KB. The packet blocking term is bytes/1000.
 */
public final class DeadlineSatisfiability {
    /** Project assumption for iota_max; the paper defines the bound but gives no numeric value. */
    public static final int DEFAULT_MAX_BLOCKING_PACKET_BYTES = 1500;
    private final double maxBlockingPacketBytes;

    /**
     * @param maxBlockingPacketBytes maximum best-effort packet length that can
     *                               block a non-preemptive transmission, in bytes
     */
    public DeadlineSatisfiability(double maxBlockingPacketBytes) {
        if (!Double.isFinite(maxBlockingPacketBytes) || maxBlockingPacketBytes < 0.0) {
            throw new IllegalArgumentException("Maximum blocking packet size must be finite and non-negative");
        }
        this.maxBlockingPacketBytes = maxBlockingPacketBytes;
    }

    public double getMaxBlockingPacketBytes() {
        return maxBlockingPacketBytes;
    }

    /**
     * Corollary 2: test the resource inequality over every t >= min delta_f^(h).
     * The test is exact for the piecewise-linear token-bucket envelopes used by
     * this project; it checks each breakpoint and its right-hand limit, and the
     * asymptotic slope, rather than sampling a fixed time grid.
     */
    public boolean isSatisfied(List<FlowHopAllocation> allocations, double capacityMbps) {
        // Eq. (17) - Check shifted arrival envelopes against max(0, c_h t - ell_max)
        // over the full local-hop deadline domain, including jumps and the tail.
        validateCapacity(capacityMbps);
        if (allocations == null || allocations.isEmpty()) {
            return true;
        }
        List<FlowHopAllocation> checked = validateAllocations(allocations);
        double lower = minimumBudget(checked);
        TreeSet<Double> points = breakpoints(checked, lower, capacityMbps, true);

        for (double t : points) {
            if (satisfiabilitySlack(checked, capacityMbps, t, false) < 0.0) {
                return false;
            }
            // A token bucket has a burst jump immediately after t=B_f,h.
            if (satisfiabilitySlack(checked, capacityMbps, t, true) < 0.0) {
                return false;
            }
        }

        // A strictly smaller service slope eventually violates the condition,
        // regardless of how small the difference is. Do not turn a negative
        // tail slope into feasibility with an absolute epsilon.
        return serviceRateKBPerMs(capacityMbps) >= totalRateKBPerMs(checked);
    }

    /**
     * Paper Eq. (20): inf_{s > min delta} [c_h s - sum Ahat_f(s-delta_f^(h)) - ell_max].
     * For no flows the paper's min over an empty set is undefined; positive
     * infinity is used as the observation sentinel (no deadline reservations).
     */
    public double calculateRemainingResource(List<FlowHopAllocation> allocations,
                                             double capacityMbps) {
        // Eq. (20) - Compute the infimum of service minus locally shifted envelopes and ell_max.
        validateCapacity(capacityMbps);
        if (allocations == null || allocations.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        List<FlowHopAllocation> checked = validateAllocations(allocations);
        double lower = minimumBudget(checked);
        double rateDifference = serviceRateKBPerMs(capacityMbps) - totalRateKBPerMs(checked);
        if (rateDifference < 0.0) {
            return Double.NEGATIVE_INFINITY;
        }

        TreeSet<Double> points = breakpoints(checked, lower, capacityMbps, false);
        double minimum = Double.POSITIVE_INFINITY;
        for (double s : points) {
            // Exact value captures the left side of an activation breakpoint.
            minimum = Math.min(minimum, remainingResourceAt(checked, capacityMbps, s, false));
            // Right limit captures the burst activated just after the breakpoint.
            minimum = Math.min(minimum, remainingResourceAt(checked, capacityMbps, s, true));
        }
        return minimum;
    }

    private TreeSet<Double> breakpoints(List<FlowHopAllocation> allocations,
                                        double lower, double capacityMbps,
                                        boolean includeServiceKink) {
        TreeSet<Double> points = new TreeSet<>();
        points.add(lower);
        for (FlowHopAllocation allocation : allocations) {
            if (allocation.getSingleHopDeadlineBudgetMs() >= lower) {
                points.add(allocation.getSingleHopDeadlineBudgetMs());
            }
        }
        // Corollary 2's max(0, c*t-ell) changes slope at ell/c.
        if (includeServiceKink && capacityMbps > 0.0) {
            double kink = (maxBlockingPacketBytes / 1000.0) / serviceRateKBPerMs(capacityMbps);
            if (kink >= lower && Double.isFinite(kink)) {
                points.add(kink);
            }
        }
        return points;
    }

    private double satisfiabilitySlack(List<FlowHopAllocation> allocations,
                                       double capacityMbps, double time,
                                       boolean rightLimit) {
        double ellKB = maxBlockingPacketBytes / 1000.0;
        double service = Math.max(0.0, serviceRateKBPerMs(capacityMbps) * time - ellKB);
        return service - arrivalSum(allocations, time, rightLimit);
    }

    private double remainingResourceAt(List<FlowHopAllocation> allocations,
                                       double capacityMbps, double time,
                                       boolean rightLimit) {
        return serviceRateKBPerMs(capacityMbps) * time
                - arrivalSum(allocations, time, rightLimit)
                - maxBlockingPacketBytes / 1000.0;
    }

    private double arrivalSum(List<FlowHopAllocation> allocations,
                              double time, boolean rightLimit) {
        double sum = 0.0;
        for (FlowHopAllocation allocation : allocations) {
            double shiftedTime = time - allocation.getSingleHopDeadlineBudgetMs();
            if (shiftedTime < 0.0 || (shiftedTime == 0.0 && !rightLimit)) {
                continue;
            }
            // Eq. (2) inside Eqs. (17)/(20): use b_f + r_f (t - local delta).
            sum += allocation.getFlow().getBurstKB()
                    + allocation.getFlow().getRateMbps() * 0.125 * Math.max(0.0, shiftedTime);
        }
        return sum;
    }

    private double totalRateKBPerMs(List<FlowHopAllocation> allocations) {
        double sum = 0.0;
        for (FlowHopAllocation allocation : allocations) {
            sum += allocation.getFlow().getRateMbps() * 0.125;
        }
        return sum;
    }

    private double serviceRateKBPerMs(double capacityMbps) {
        return capacityMbps * 0.125;
    }

    private double minimumBudget(List<FlowHopAllocation> allocations) {
        double minimum = Double.POSITIVE_INFINITY;
        for (FlowHopAllocation allocation : allocations) {
            minimum = Math.min(minimum, allocation.getSingleHopDeadlineBudgetMs());
        }
        return minimum;
    }

    private List<FlowHopAllocation> validateAllocations(List<FlowHopAllocation> allocations) {
        List<FlowHopAllocation> copy = new ArrayList<>(allocations);
        if (copy.contains(null)) {
            throw new IllegalArgumentException("Allocations cannot contain null");
        }
        for (FlowHopAllocation allocation : copy) {
            if (!Double.isFinite(allocation.getFlow().getRateMbps()) || allocation.getFlow().getRateMbps() < 0.0
                    || !Double.isFinite(allocation.getFlow().getBurstKB()) || allocation.getFlow().getBurstKB() < 0.0) {
                throw new IllegalArgumentException("Flow rates and bursts must be finite and non-negative");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private void validateCapacity(double capacityMbps) {
        if (!Double.isFinite(capacityMbps) || capacityMbps < 0.0) {
            throw new IllegalArgumentException("Link capacity must be finite and non-negative");
        }
    }

}
