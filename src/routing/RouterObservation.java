package routing;

import java.util.ArrayList;
import java.util.List;

import network.Router;
import traffic.TrafficFlow;

public class RouterObservation {

    private TrafficFlow flow;

    private List<PathDecision> previousDecisions;

    private List<NeighborState> neighborStates;

    public RouterObservation(
            TrafficFlow flow,
            List<PathDecision> previousDecisions,
            List<NeighborState> neighborStates) {

        this.flow = flow;

        this.previousDecisions =
                new ArrayList<>(previousDecisions);

        this.neighborStates =
                new ArrayList<>(neighborStates);
    }

    public TrafficFlow getFlow() {
        return flow;
    }

    public List<PathDecision> getPreviousDecisions() {
        return previousDecisions;
    }

    public List<NeighborState> getNeighborStates() {
        return neighborStates;
    }

    public void addPathDecision(PathDecision decision) {
        previousDecisions.add(decision);
    }

    public Router getCurrentRouter() {
        if (previousDecisions.isEmpty()) {
            return flow.getSource();
        }

        return previousDecisions
                .get(previousDecisions.size() - 1)
                .getNextHop();
    }

    public static class NeighborState {

        private Router neighbor;

        private double propagationDelay;

        private double processingDelay;

        private int hopDistance;

        private double remainingDeadlineResource;

        public NeighborState(
                Router neighbor,
                double propagationDelay,
                double processingDelay,
                int hopDistance,
                double remainingDeadlineResource) {

            this.neighbor = neighbor;
            this.propagationDelay = propagationDelay;
            this.processingDelay = processingDelay;
            this.hopDistance = hopDistance;
            this.remainingDeadlineResource =
                    remainingDeadlineResource;
        }

        public Router getNeighbor() {
            return neighbor;
        }

        public double getPropagationDelay() {
            return propagationDelay;
        }

        public double getProcessingDelay() {
            return processingDelay;
        }

        public int getHopDistance() {
            return hopDistance;
        }

        public double getRemainingDeadlineResource() {
            return remainingDeadlineResource;
        }

        @Override
        public String toString() {
            return neighbor.getName() + " | Propagation = "
                    + propagationDelay
                    + " ms | Processing = "
                    + processingDelay
                    + " ms | Hop Distance = "
                    + hopDistance
                    + " | Phi = "
                    + remainingDeadlineResource;
        }
    }
}