package routing;

import network.Router;

public class PathDecision {

    private Router router;
    private Router nextHop;
    private double deadlineBudget;

    public PathDecision(
            Router router,
            Router nextHop,
            double deadlineBudget) {

        this.router = router;
        this.nextHop = nextHop;
        this.deadlineBudget = deadlineBudget;
    }

    public Router getRouter() {
        return router;
    }
    public Router getNextHop() {
        return nextHop;
    }
    public double getDeadlineBudget() {
        return deadlineBudget;
    }
    @Override
    public String toString() {
        return router.getName()+ " -> "+ nextHop.getName()+ " | Budget = "
                + deadlineBudget
                + " ms";
    }
}