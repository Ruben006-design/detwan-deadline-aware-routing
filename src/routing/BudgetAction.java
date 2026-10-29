package routing;

import network.Router;

public class BudgetAction {

    private Router nextHop;
    private double deadlineBudget;

    public BudgetAction(
            Router nextHop,
            double deadlineBudget) {

        this.nextHop = nextHop;
        this.deadlineBudget = deadlineBudget;
    }

    public Router getNextHop() {
        return nextHop;
    }

    public double getDeadlineBudget() {
        return deadlineBudget;
    }

    @Override
    public String toString() {

        return "Next Hop = "
                + nextHop.getName()
                + " | Deadline Budget = "
                + deadlineBudget
                + " ms";
    }
}