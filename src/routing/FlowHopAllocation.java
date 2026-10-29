package routing;

import network.Router;
import traffic.TrafficFlow;

/** Reservation of a flow on one directed hop. */
public final class FlowHopAllocation {
    private final TrafficFlow flow;
    private final Router source;
    private final Router destination;
    private final double singleHopDeadlineBudgetMs;
    private final double cumulativeDeadlineBudgetMs;

    public FlowHopAllocation(TrafficFlow flow, Router source, Router destination,
                             double singleHopDeadlineBudgetMs,
                             double cumulativeDeadlineBudgetMs) {
        if (flow == null || source == null || destination == null) {
            throw new IllegalArgumentException("Flow and link endpoints are required");
        }
        if (!Double.isFinite(singleHopDeadlineBudgetMs) || singleHopDeadlineBudgetMs < 0.0) {
            throw new IllegalArgumentException("Single-hop deadline budget must be finite and non-negative");
        }
        if (!Double.isFinite(cumulativeDeadlineBudgetMs) || cumulativeDeadlineBudgetMs < 0.0) {
            throw new IllegalArgumentException("Cumulative deadline budget must be finite and non-negative");
        }
        this.flow = flow;
        this.source = source;
        this.destination = destination;
        this.singleHopDeadlineBudgetMs = singleHopDeadlineBudgetMs;
        this.cumulativeDeadlineBudgetMs = cumulativeDeadlineBudgetMs;
    }

    public TrafficFlow getFlow() { return flow; }
    public Router getSource() { return source; }
    public Router getDestination() { return destination; }
    public double getSingleHopDeadlineBudgetMs() { return singleHopDeadlineBudgetMs; }
    public double getCumulativeDeadlineBudgetMs() { return cumulativeDeadlineBudgetMs; }

    public boolean usesLink(Router linkSource, Router linkDestination) {
        return source.equals(linkSource) && destination.equals(linkDestination);
    }
}
