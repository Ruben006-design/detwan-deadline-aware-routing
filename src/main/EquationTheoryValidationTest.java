import java.util.*;
import network.Router;
import routing.DeadlineCurve;
import routing.EssentialTraffic;
import routing.FlowHopAllocation;
import routing.SchedulabilityCriterion;
import traffic.TrafficFlow;

public class EquationTheoryValidationTest {

    public static void main(String[] args) {
        testEq5DeadlineCurve();
        testEq7EssentialTraffic();
        testEq8Theorem1();
        testEq14Corollary1();
        System.out.println("EquationTheoryValidationTest passed successfully.");
    }

    private static void testEq5DeadlineCurve() {
        Router r1 = new Router(1, "R1");
        Router r2 = new Router(2, "R2");
        TrafficFlow flow = new TrafficFlow(1, r1, r2, 20.0, 50.0, 10.0); // 2.5 KB/ms, 50 KB, 10 ms

        // Eq. (5): D_f(t) = A_f(t - delta)
        assertEquals(0.0, DeadlineCurve.calculate(flow, 2.0, 4.0), 1e-12, "D_f for t <= delta must be 0");
        assertEquals(0.0, DeadlineCurve.calculate(flow, 4.0, 4.0), 1e-12, "D_f at t = delta must be 0");
        assertEquals(55.0, DeadlineCurve.calculate(flow, 6.0, 4.0), 1e-12, "D_f at t=6 with delta=4 should be 55.0 KB");
        assertEquals(55.0, DeadlineCurve.calculate(flow, 9.0, 7.0), 1e-12, "D_f multi-hop cumulative shift check");
    }

    private static void testEq7EssentialTraffic() {
        Router r1 = new Router(1, "R1");
        Router r2 = new Router(2, "R2");
        TrafficFlow flow = new TrafficFlow(1, r1, r2, 8.0, 40.0, 15.0); // 1 KB/ms, 40 KB

        // Eq. (7): E_f^h(u, v) = max{0, D_f(v) - A_f(u)}
        assertEquals(3.0, EssentialTraffic.calculate(flow, 2.0, 10.0, 5.0), 1e-12, "Essential traffic E(2, 10)");
        assertEquals(0.0, EssentialTraffic.calculate(flow, 10.0, 5.0, 5.0), 1e-12, "Essential traffic for v < u");
        assertEquals(0.0, EssentialTraffic.calculate(flow, -1.0, 5.0, 5.0), 1e-12, "Essential traffic for u < 0");
        assertEquals(0.0, EssentialTraffic.calculate(flow, 4.0, 6.0, 5.0), 1e-12, "Essential traffic non-negativity");

        // Eq. (14) downstream bound: max{0, D_f(v) - D_f(u + localBudget)}
        assertEquals(44.0, EssentialTraffic.calculateDownstreamBound(flow, 2.0, 12.0, 3.0, 8.0), 1e-12, "Downstream bound u=2, v=12");
        assertEquals(2.0, EssentialTraffic.calculateDownstreamBound(flow, 7.0, 12.0, 3.0, 8.0), 1e-12, "Downstream bound u=7, v=12");
        assertEquals(0.0, EssentialTraffic.calculateDownstreamBound(flow, 10.0, 5.0, 3.0, 8.0), 1e-12, "Downstream bound v < u");
    }

    private static void testEq8Theorem1() {
        Router r1 = new Router(1, "R1");
        Router r2 = new Router(2, "R2");
        TrafficFlow flow = new TrafficFlow(1, r1, r2, 8.0, 40.0, 15.0); // 1 KB/ms, 40 KB
        FlowHopAllocation alloc = new FlowHopAllocation(flow, r1, r2, 5.0, 5.0);
        List<FlowHopAllocation> list = Collections.singletonList(alloc);

        SchedulabilityCriterion criterion = new SchedulabilityCriterion(1500.0);
        // Feasible link: service = 12.5 * 8 - 1.5 = 98.5 KB >= 3.0 KB
        assertTrue(criterion.checkTheorem1Interval(list, 100.0, 2.0, 10.0), "Feasible link should pass");
        // Over-constrained: service = 0 KB < 3.0 KB
        assertTrue(!criterion.checkTheorem1Interval(list, 0.4, 2.0, 10.0), "Insufficient capacity should fail");
        // Invalid inputs
        assertTrue(!criterion.checkTheorem1Interval(list, 100.0, 5.0, 2.0), "v < u should return false");
        assertTrue(!criterion.checkTheorem1Interval(list, 100.0, -1.0, 5.0), "u < 0 should return false");
        assertTrue(!criterion.checkTheorem1Interval(list, 0.0, 1.0, 5.0), "capacity <= 0 should return false");
    }

    private static void testEq14Corollary1() {
        Router r1 = new Router(1, "R1");
        Router r2 = new Router(2, "R2");
        TrafficFlow flow = new TrafficFlow(1, r1, r2, 8.0, 40.0, 15.0); // 1 KB/ms, 40 KB
        FlowHopAllocation alloc = new FlowHopAllocation(flow, r1, r2, 5.0, 5.0);
        List<FlowHopAllocation> list = Collections.singletonList(alloc);

        SchedulabilityCriterion criterion = new SchedulabilityCriterion(1500.0);
        assertTrue(criterion.checkCorollary1Interval(list, 100.0, 1.0, 9.0), "Corollary 1 should pass");
        assertTrue(!criterion.checkCorollary1Interval(list, 0.4, 1.0, 9.0), "Insufficient capacity should fail");
        assertTrue(!criterion.checkCorollary1Interval(list, 100.0, 5.0, 2.0), "v < u should return false");
        assertTrue(!criterion.checkCorollary1Interval(list, 100.0, -1.0, 5.0), "u < 0 should return false");
        assertTrue(!criterion.checkCorollary1Interval(list, 0.0, 1.0, 5.0), "capacity <= 0 should return false");
    }

    private static void assertEquals(double expected, double actual, double tol, String msg) {
        if (Math.abs(expected - actual) > tol) throw new AssertionError(msg + ": expected " + expected + ", got " + actual);
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
