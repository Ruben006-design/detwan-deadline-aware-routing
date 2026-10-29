package routing;

import java.util.ArrayList;
import java.util.List;

public class MAPPOEpisodeResult {

    private final boolean routeComplete;
    private final boolean routeQualified;
    private final List<PathDecision> pathDecisions;
    private final TrajectoryBuffer trajectory;
    private final double totalReward;
    private final String terminationReason;
    private final String rejectionReason;

    public MAPPOEpisodeResult(
            boolean routeComplete,
            boolean routeQualified,
            List<PathDecision> pathDecisions,
            TrajectoryBuffer trajectory,
            double totalReward) {

        this(routeComplete, routeQualified, pathDecisions, trajectory,
                totalReward,
                routeComplete ? "DESTINATION_REACHED" : "ROUTE_INCOMPLETE",
                routeQualified ? null : "ROUTE_NOT_QUALIFIED");
    }

    public MAPPOEpisodeResult(
            boolean routeComplete,
            boolean routeQualified,
            List<PathDecision> pathDecisions,
            TrajectoryBuffer trajectory,
            double totalReward,
            String terminationReason,
            String rejectionReason) {

        this.routeComplete = routeComplete;
        this.routeQualified = routeQualified;
        this.pathDecisions = new ArrayList<>(pathDecisions);
        this.trajectory = trajectory;
        this.totalReward = totalReward;
        this.terminationReason = terminationReason;
        this.rejectionReason = rejectionReason;
    }

    public boolean isRouteComplete() {
        return routeComplete;
    }

    public boolean isRouteQualified() {
        return routeQualified;
    }

    public List<PathDecision> getPathDecisions() {
        return new ArrayList<>(pathDecisions);
    }

    public TrajectoryBuffer getTrajectory() {
        return trajectory;
    }

    public double getTotalReward() {
        return totalReward;
    }

    public String getTerminationReason() {
        return terminationReason;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public double getAccumulatedDeadlineBudgetMs() {
        double total = 0.0;
        for (PathDecision decision : pathDecisions) {
            total += decision.getDeadlineBudget();
        }
        return total;
    }
}
