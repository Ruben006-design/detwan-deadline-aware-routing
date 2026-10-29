package routing;

import java.util.ArrayList;
import java.util.List;

public class DeadlineBudget {

    private double endToEndDeadline;
    private int budgetLevels;

    public DeadlineBudget(double endToEndDeadline, int budgetLevels) {
        this.endToEndDeadline = endToEndDeadline;
        this.budgetLevels = budgetLevels;
    }

    public double getEndToEndDeadline() {
        return endToEndDeadline;
    }

    public int getBudgetLevels() {
        return budgetLevels;
    }

    public List<Double> getPossibleBudgets() {
        List<Double> budgets = new ArrayList<>();
        if (budgetLevels <= 0) {
            return budgets;
        }

        for (int m = 1; m <= budgetLevels; m++) {
            // Eq. (22) - Discretize D_f into levels D_f/M, ..., D_f.
            double budget = (endToEndDeadline * m) / budgetLevels;
            budgets.add(budget);
        }

        return budgets;
    }

    public boolean isValidBudget(double budget) {
        if (budget <= 0) {
            return false;
        }
        if (budget > endToEndDeadline) {
            return false;
        }

        double step = endToEndDeadline / budgetLevels;
        double index = budget / step;
        return Math.abs(index - Math.round(index)) < 0.000001;
    }
}
