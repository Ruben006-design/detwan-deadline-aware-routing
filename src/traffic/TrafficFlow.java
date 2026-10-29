package traffic;

import network.Router;

public class TrafficFlow {

    private int flowId;
    private Router source;
    private Router destination;
    private double dataRate;
    private double burstSize;
    private double deadline;
    private double sourceAccessDelayMs;
    private double destinationAccessDelayMs;

    public TrafficFlow(
            int flowId,
            Router source,
            Router destination,
            double dataRate,
            double burstSize,
            double deadline) {

        this(flowId, source, destination, dataRate, burstSize, deadline, 0.0, 0.0);
    }

    /**
     * Creates a flow with explicit access-layer delay contributions. The
     * legacy constructor uses zero for both, preserving the project's former
     * router-to-router-only deadline behavior.
     */
    public TrafficFlow(
            int flowId,
            Router source,
            Router destination,
            double dataRate,
            double burstSize,
            double deadline,
            double sourceAccessDelayMs,
            double destinationAccessDelayMs) {

        if (!Double.isFinite(sourceAccessDelayMs) || sourceAccessDelayMs < 0.0
                || !Double.isFinite(destinationAccessDelayMs)
                || destinationAccessDelayMs < 0.0) {
            throw new IllegalArgumentException(
                    "Endpoint access delays must be finite and non-negative");
        }

        this.flowId = flowId;
        this.source = source;
        this.destination = destination;
        this.dataRate = dataRate;
        this.burstSize = burstSize;
        this.deadline = deadline;
        this.sourceAccessDelayMs = sourceAccessDelayMs;
        this.destinationAccessDelayMs = destinationAccessDelayMs;
    }

    public int getFlowId() {
        return flowId;
    }

    public Router getSource() {
        return source;
    }

    public Router getDestination() {
        return destination;
    }

    public double getRateMbps() {
        return dataRate;
    }

    public double getBurstKB() {
        return burstSize;
    }

    public double getDeadline() {
        return deadline;
    }

    public double getSourceAccessDelayMs() {
        return sourceAccessDelayMs;
    }

    public double getDestinationAccessDelayMs() {
        return destinationAccessDelayMs;
    }

    public double getTotalEndpointAccessDelayMs() {
        return sourceAccessDelayMs + destinationAccessDelayMs;
    }

    @Override
    public String toString() {
        return "Flow " + flowId +
                ": " + source.getName() +
                " -> " + destination.getName() +
                " | Rate = " + dataRate + " Mbps" +
                " | Burst = " + burstSize + " KB" +
                " | Deadline = " + deadline + " ms" +
                " | Access delays = " + sourceAccessDelayMs + "+"
                + destinationAccessDelayMs + " ms";
    }
}
