package routing;

import java.util.List;

/**
 * Validates sufficient deadline satisfiability criteria:
 * - Eq. (8) Theorem 1: single-hop condition based on essential traffic E_f^h(u, v)
 * - Eq. (14) Corollary 1: multi-hop condition based on deadline curves D_f^{(k)}
 * (c_h: link capacity Mbps, u/v: interval ms, \iota_max: max blocking packet bytes)
 */
public final class SchedulabilityCriterion {

    private final double maxBlockingPacketBytes;

    public SchedulabilityCriterion(double maxBlockingPacketBytes) {
        if (!Double.isFinite(maxBlockingPacketBytes) || maxBlockingPacketBytes < 0.0) {
            throw new IllegalArgumentException("Max blocking packet bytes must be non-negative");
        }
        this.maxBlockingPacketBytes = maxBlockingPacketBytes;
    }

    /** Eq. (8) Theorem 1: \sum_{f \in F_h} E_f^h(u, v) <= max{0, c_h (v - u) - \iota_max} */
    public boolean checkTheorem1Interval(List<FlowHopAllocation> allocations, double capacityMbps, double u, double v) {
        if (capacityMbps <= 0.0 || u < 0.0 || v < u || !Double.isFinite(capacityMbps) || !Double.isFinite(u) || !Double.isFinite(v)) return false;
        double totalEssentialTraffic = 0.0;
        if (allocations != null) {
            for (FlowHopAllocation alloc : allocations) {
                totalEssentialTraffic += EssentialTraffic.calculate(alloc.getFlow(), u, v, alloc.getCumulativeDeadlineBudgetMs());
            }
        }
        double availableServiceKB = Math.max(0.0, capacityMbps * 0.125 * (v - u) - (maxBlockingPacketBytes / 1000.0));
        return totalEssentialTraffic <= availableServiceKB + 1e-12;
    }

    /** Eq. (14) Corollary 1: \sum_{f \in F_h} max{0, D_f^{(k)}(v) - D_f^{(k)}(u + \delta_f^{(k)})} <= max{0, c_h (v - u) - \iota_max} */
    public boolean checkCorollary1Interval(List<FlowHopAllocation> allocations, double capacityMbps, double u, double v) {
        if (capacityMbps <= 0.0 || u < 0.0 || v < u || !Double.isFinite(capacityMbps) || !Double.isFinite(u) || !Double.isFinite(v)) return false;
        double totalShiftedTraffic = 0.0;
        if (allocations != null) {
            for (FlowHopAllocation alloc : allocations) {
                totalShiftedTraffic += EssentialTraffic.calculateDownstreamBound(alloc.getFlow(), u, v, alloc.getSingleHopDeadlineBudgetMs(), alloc.getCumulativeDeadlineBudgetMs());
            }
        }
        double availableServiceKB = Math.max(0.0, capacityMbps * 0.125 * (v - u) - (maxBlockingPacketBytes / 1000.0));
        return totalShiftedTraffic <= availableServiceKB + 1e-12;
    }
}
