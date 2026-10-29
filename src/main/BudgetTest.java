import routing.DeadlineBudget;
import java.util.List;

public class BudgetTest {

    public static void main(String[] args) {

        DeadlineBudget budget =
                new DeadlineBudget(10.0, 5);

        List<Double> budgets =
                budget.getPossibleBudgets();

        System.out.println("E2E Deadline = "
                + budget.getEndToEndDeadline()
                + " ms");

        System.out.println("Budget Levels = "
                + budget.getBudgetLevels());

        System.out.println("\nPossible Deadline Budgets:");

        for (double value : budgets) {

            System.out.println(
                    value + " ms"
            );
        }
    }
}