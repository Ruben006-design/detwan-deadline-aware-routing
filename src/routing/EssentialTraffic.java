package routing;

import traffic.TrafficFlow;

/**
 * Eq. (7): E_f^h(u, v) = max{0, D_f^{(k_{f,h})}(v) - A_f^{(k_{f,h})}(u)}
 * u, v: interval start and end times in ms (v >= u >= 0).
 * cumulativeBudget: \sum_{n=1}^k \delta_f^{(n)} in ms.
 *
 * For single-hop (ingress, k=1), A_f^{(1)}(u) follows ArrivalEnvelope(u).
 * For downstream hops (k > 1), Corollaries 1 & 2 bound A_f^{(k)}(u) by D_f^{(k)}(u + \delta_f^{(k)}).
 */
public final class EssentialTraffic {
    private EssentialTraffic() {}

    public static double calculate(TrafficFlow flow, double u, double v, double cumulativeBudget) {
        if (flow == null || !Double.isFinite(u) || !Double.isFinite(v) || v < u || u < 0.0) {
            return 0.0;
        }
        // At ingress (cumulative budget equals single hop budget), A_f^(1)(u) is the arrival curve
        double dV = DeadlineCurve.calculate(flow, v, cumulativeBudget);
        double aU = u > 0.0 ? ArrivalEnvelope.calculate(flow, u) : 0.0;
        return Math.max(0.0, dV - aU);
    }

    /**
     * Eq. (14) bounded essential traffic at downstream hop k:
     * Uses A_f^{(k)}(u) >= D_f^{(k)}(u + \delta_f^{(k)}) from paper Eq. (15).
     */
    public static double calculateDownstreamBound(TrafficFlow flow, double u, double v,
                                                  double localBudget, double cumulativeBudget) {
        if (flow == null || !Double.isFinite(u) || !Double.isFinite(v) || v < u || u < 0.0) {
            return 0.0;
        }
        double dV = DeadlineCurve.calculate(flow, v, cumulativeBudget);
        double dU = DeadlineCurve.calculate(flow, u + localBudget, cumulativeBudget);
        return Math.max(0.0, dV - dU);
    }
}