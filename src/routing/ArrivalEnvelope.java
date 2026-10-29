package routing;

import traffic.TrafficFlow;

public class ArrivalEnvelope {

    public static double calculate(TrafficFlow flow, double time) {
        if (time <= 0) {
            return 0.0;
        }
        double rateMbps = flow.getRateMbps();
        double burstKB = flow.getBurstKB();

        // Eq. (2) - The project token-bucket envelope is r_f t + b_f (decimal KB).
        double rateKBPerMs = rateMbps * 0.125;
        return rateKBPerMs * time + burstKB;
    }
}
