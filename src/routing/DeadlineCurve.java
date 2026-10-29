package routing;

import traffic.TrafficFlow;

/**
 * Eq. (5): D_f^(k)(t) = A_f^(1)(t - \sum_{n=1}^k \delta_f^{(n)})
 * Evaluates the shifted ingress arrival envelope.
 * t: time in ms; cumulativeBudget: \sum_{n=1}^k \delta in ms.
 */
public final class DeadlineCurve {
    private DeadlineCurve() {}

    public static double calculate(TrafficFlow flow, double t, double cumulativeBudget) {
        if (flow == null || !Double.isFinite(t) || !Double.isFinite(cumulativeBudget) || cumulativeBudget < 0.0) {
            return 0.0;
        }
        double shiftedTime = t - cumulativeBudget;
        return shiftedTime <= 0.0 ? 0.0 : ArrivalEnvelope.calculate(flow, shiftedTime);
    }
}