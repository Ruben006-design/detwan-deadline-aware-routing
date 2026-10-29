import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.DeadlineSatisfiability;
import routing.FlowHopAllocation;
import routing.NetworkState;
import routing.PathDecision;
import routing.RouteCascade;
import traffic.TrafficFlow;

/** Focused tests for paper-based per-link deadline admission. */
public class DeadlineSatisfiabilityTest {
    public static void main(String[] args) {
        testRouteSatisfiesDeadlineCondition();
        testRouteDeadlineBudgetViolation();
        testInsufficientLinkCapacity();
        testNonPreemptivePacketContribution();
        testExactRemainingResource();
        testConfiguredPacketSizeIsSharedByAdmissionAndObservation();
        testOnlyFlowsSharingTheLinkConsumeItsResource();
        testLocalBudgetUsedOnLaterHopForEq17AndEq20();
        testEq17EqualityAtBurstBreakpoint();
        testEq17EqualityAtAsymptoticRateBoundary();
        testEq17RejectsSubEpsilonBurstExcess();
        testEq17RejectsSubEpsilonRateExcess();
        testEq20ZeroAndNegativeTailSlopes();
        testRouteBudgetBoundaries();
        testEndpointAccessDelayDeadlineBoundaries();
        testEndpointDelayFailureDoesNotReserveRoute();
        testReservationRollbackOnLaterHopFailure();
        testReverseDirectionReservationsAreIndependent();
        testRepeatedFlowIdReplacementBehavior();
        System.out.println("All deadline satisfiability tests passed.");
    }

    private static void testRouteSatisfiesDeadlineCondition() {
        Fixture f = new Fixture(100.0);
        TrafficFlow flow = f.flow(1, f.a, f.c, 1.0, 1.0, 4.0);
        List<PathDecision> route = Arrays.asList(
                new PathDecision(f.a, f.b, 1.0),
                new PathDecision(f.b, f.c, 1.0));
        assertTrue(f.cascade.checkRouteQualification(flow, route),
                "low-load two-hop route should pass");
    }

    private static void testRouteDeadlineBudgetViolation() {
        Fixture f = new Fixture(100.0);
        TrafficFlow flow = f.flow(2, f.a, f.c, 1.0, 0.1, 1.5);
        List<PathDecision> route = Arrays.asList(
                new PathDecision(f.a, f.b, 1.0),
                new PathDecision(f.b, f.c, 1.0));
        assertFalse(f.cascade.checkRouteQualification(flow, route),
                "route whose budget sum exceeds the end-to-end deadline must fail");
    }

    private static void testInsufficientLinkCapacity() {
        Fixture f = new Fixture(10.0);
        TrafficFlow flow = f.flow(3, f.a, f.b, 10.0, 50.0, 5.0);
        List<PathDecision> route = Collections.singletonList(
                new PathDecision(f.a, f.b, 1.0));
        assertFalse(f.cascade.checkRouteQualification(flow, route),
                "burst/rate load above link capacity must fail");
    }

    private static void testNonPreemptivePacketContribution() {
        Router a = new Router(10, "NP-A");
        Router b = new Router(11, "NP-B");
        TrafficFlow burst = new TrafficFlow(4, a, b, 0.0, 10.0, 5.0);
        List<FlowHopAllocation> oneFlow = Collections.singletonList(
                new FlowHopAllocation(burst, a, b, 1.0, 1.0));

        boolean withoutBlockingTerm = new DeadlineSatisfiability(0.0)
                .isSatisfied(oneFlow, 100.0);
        boolean withBlockingTerm = new DeadlineSatisfiability(3000.0)
                .isSatisfied(oneFlow, 100.0);
        assertTrue(withoutBlockingTerm, "control case without blocking term should pass");
        assertFalse(withBlockingTerm,
                "3 KB non-preemptive blocking packet must reduce service enough to fail");
    }

    private static void testExactRemainingResource() {
        Router a = new Router(12, "PHI-A");
        Router b = new Router(13, "PHI-B");
        TrafficFlow burst = new TrafficFlow(8, a, b, 0.0, 2.0, 5.0);
        List<FlowHopAllocation> oneFlow = Collections.singletonList(
                new FlowHopAllocation(burst, a, b, 1.0, 1.0));
        double phi = new DeadlineSatisfiability(1500.0)
                .calculateRemainingResource(oneFlow, 10.0);
        // At s -> 1 ms from above: 10 Mbps * .125 KB/ms - 2 KB - 1.5 KB.
        assertNear(-2.25, phi, 1.0e-9,
                "Eq. (20) should be minimized at the activation breakpoint right-limit");
    }

    private static void testConfiguredPacketSizeIsSharedByAdmissionAndObservation() {
        Router a = new Router(20, "CONFIG-A");
        Router b = new Router(21, "CONFIG-B");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a);
        topology.addRouter(b);
        topology.addLink(new Link(a, b, 0.1, 100.0));

        TrafficFlow flow = new TrafficFlow(20, a, b, 0.0, 10.0, 5.0);
        List<PathDecision> route = Collections.singletonList(
                new PathDecision(a, b, 1.0));
        List<FlowHopAllocation> allocations = Collections.singletonList(
                new FlowHopAllocation(flow, a, b, 1.0, 1.0));

        RouteCascade noBlocking = new RouteCascade(topology, 0.1, 0.0);
        RouteCascade threeKbBlocking = new RouteCascade(topology, 0.1, 3000.0);
        assertTrue(noBlocking.checkRouteQualification(flow, route),
                "admission should pass when no blocking packet is configured");
        assertFalse(threeKbBlocking.checkRouteQualification(flow, route),
                "same admission should fail with a 3 KB blocking packet");

        double phiWithoutBlocking = new NetworkState(topology, 0.1, 0.0)
                .getNeighborStates(a, b, allocations)
                .get(0).getRemainingDeadlineResource();
        double phiWithThreeKbBlocking = new NetworkState(topology, 0.1, 3000.0)
                .getNeighborStates(a, b, allocations)
                .get(0).getRemainingDeadlineResource();
        assertNear(2.5, phiWithoutBlocking, 1.0e-9,
                "Eq. (20) should use the configured zero-byte term");
        assertNear(-0.5, phiWithThreeKbBlocking, 1.0e-9,
                "Eq. (20) should use the same configured 3 KB term");
    }

    private static void testOnlyFlowsSharingTheLinkConsumeItsResource() {
        Fixture f = new Fixture(100.0);
        TrafficFlow first = f.flow(5, f.a, f.b, 60.0, 10.0, 5.0);
        List<PathDecision> firstRoute = Collections.singletonList(
                new PathDecision(f.a, f.b, 1.0));
        assertTrue(f.cascade.admitRoute(first, firstRoute), "first flow should be admitted");

        // An equally demanding flow on a separate directed link does not consume A->B resource.
        TrafficFlow separate = f.flow(6, f.c, f.d, 60.0, 10.0, 5.0);
        List<PathDecision> separateRoute = Collections.singletonList(
                new PathDecision(f.c, f.d, 1.0));
        assertTrue(f.cascade.admitRoute(separate, separateRoute),
                "unrelated flow must not reduce A->B capacity");

        TrafficFlow sharing = f.flow(7, f.a, f.b, 60.0, 10.0, 5.0);
        assertFalse(f.cascade.checkRouteQualification(sharing, firstRoute),
                "second flow sharing A->B must be rejected for insufficient aggregate resource");
    }

    private static void testLocalBudgetUsedOnLaterHopForEq17AndEq20() {
        Router a = new Router(30, "LOCAL-A");
        Router b = new Router(31, "LOCAL-B");
        Router c = new Router(32, "LOCAL-C");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a);
        topology.addRouter(b);
        topology.addRouter(c);
        topology.addLink(new Link(a, b, 0.1, 100.0));
        topology.addLink(new Link(b, c, 0.1, 10.0));

        TrafficFlow flow = new TrafficFlow(30, a, c, 0.0, 2.0, 5.0);
        List<PathDecision> route = Arrays.asList(
                new PathDecision(a, b, 2.0),
                new PathDecision(b, c, 2.0));

        RouteCascade cascade = new RouteCascade(topology, 0.1, 1500.0);
        String failure = cascade.getQualificationFailureReason(flow, route);
        assertTrue(failure != null && failure.contains("EQ17_DEADLINE_SATISFIABILITY_FAILED on LOCAL-B -> LOCAL-C"),
                "second-hop Eq. (17) must use local delta=2ms, not cumulative B=4ms: " + failure);

        FlowHopAllocation local = new FlowHopAllocation(flow, b, c, 2.0, 4.0);
        FlowHopAllocation oldCumulativeInterpretation = new FlowHopAllocation(flow, b, c, 4.0, 4.0);
        DeadlineSatisfiability check = new DeadlineSatisfiability(1500.0);
        assertFalse(check.isSatisfied(Collections.singletonList(local), 10.0),
                "local delta=2ms must fail at the burst activation breakpoint");
        assertTrue(check.isSatisfied(Collections.singletonList(oldCumulativeInterpretation), 10.0),
                "the former cumulative-budget shift would incorrectly pass this case");
        assertNear(-1.0, check.calculateRemainingResource(
                        Collections.singletonList(local), 10.0), 1.0e-9,
                "Eq. (20) must shift by local delta=2ms (2.5-2-1.5=-1 KB)");
        assertNear(1.5, check.calculateRemainingResource(
                        Collections.singletonList(oldCumulativeInterpretation), 10.0), 1.0e-9,
                "using cumulative B=4ms would incorrectly report 1.5 KB remaining");

        NetworkTopology highCapacity = new NetworkTopology();
        highCapacity.addRouter(a);
        highCapacity.addRouter(b);
        highCapacity.addRouter(c);
        highCapacity.addLink(new Link(a, b, 0.1, 100.0));
        highCapacity.addLink(new Link(b, c, 0.1, 100.0));
        RouteCascade admittedCascade = new RouteCascade(highCapacity, 0.1, 0.0);
        assertTrue(admittedCascade.admitRoute(flow, route),
                "high-capacity control route should reserve both hops");
        List<FlowHopAllocation> reservations = admittedCascade.getAdmittedAllocations();
        assertNear(2.0, reservations.get(1).getSingleHopDeadlineBudgetMs(), 1.0e-12,
                "second reservation must retain its local delta");
        assertNear(4.0, reservations.get(1).getCumulativeDeadlineBudgetMs(), 1.0e-12,
                "second reservation must retain cumulative CEDF budget B");
    }

    private static void testEq17EqualityAtBurstBreakpoint() {
        Router a = new Router(40, "BREAK-A");
        Router b = new Router(41, "BREAK-B");
        TrafficFlow flow = new TrafficFlow(40, a, b, 0.0, 1.0, 5.0);
        FlowHopAllocation allocation = new FlowHopAllocation(flow, a, b, 2.0, 2.0);
        // At t=delta=2 ms from the right: max(0, 1.25*2-1.5)=1 KB,
        // exactly equal to the activated 1 KB burst.
        assertTrue(new DeadlineSatisfiability(1500.0)
                        .isSatisfied(Collections.singletonList(allocation), 10.0),
                "Eq. (17) must admit exact equality at the burst breakpoint");
    }

    private static void testEq17EqualityAtAsymptoticRateBoundary() {
        Router a = new Router(42, "TAIL-A");
        Router b = new Router(43, "TAIL-B");
        TrafficFlow flow = new TrafficFlow(42, a, b, 10.0, 1.0, 5.0);
        FlowHopAllocation allocation = new FlowHopAllocation(flow, a, b, 2.0, 2.0);
        assertTrue(new DeadlineSatisfiability(1500.0)
                        .isSatisfied(Collections.singletonList(allocation), 10.0),
                "equal arrival/service slopes and exact burst-boundary equality must pass");
    }

    private static void testEq17RejectsSubEpsilonBurstExcess() {
        Router a = new Router(44, "BURST-A");
        Router b = new Router(45, "BURST-B");
        TrafficFlow flow = new TrafficFlow(44, a, b, 0.0, 1.0 + 5.0e-10, 5.0);
        FlowHopAllocation allocation = new FlowHopAllocation(flow, a, b, 2.0, 2.0);
        assertFalse(new DeadlineSatisfiability(1500.0)
                        .isSatisfied(Collections.singletonList(allocation), 10.0),
                "a 5e-10 KB breakpoint excess is mathematically infeasible and must not be hidden by the former 1e-9 tolerance");
    }

    private static void testEq17RejectsSubEpsilonRateExcess() {
        Router a = new Router(46, "RATE-A");
        Router b = new Router(47, "RATE-B");
        // The excess service slope is 5e-10 KB/ms, below the former 1e-9 EPSILON.
        TrafficFlow flow = new TrafficFlow(46, a, b, 10.0 + 4.0e-9, 0.0, 5.0);
        FlowHopAllocation allocation = new FlowHopAllocation(flow, a, b, 2.0, 2.0);
        assertFalse(new DeadlineSatisfiability(1500.0)
                        .isSatisfied(Collections.singletonList(allocation), 10.0),
                "any strictly greater asymptotic arrival rate must fail Eq. (17)");
    }

    private static void testEq20ZeroAndNegativeTailSlopes() {
        Router a = new Router(48, "PHI-TAIL-A");
        Router b = new Router(49, "PHI-TAIL-B");
        DeadlineSatisfiability check = new DeadlineSatisfiability(1500.0);

        TrafficFlow zeroSlope = new TrafficFlow(48, a, b, 10.0, 1.0, 5.0);
        FlowHopAllocation zeroAllocation = new FlowHopAllocation(zeroSlope, a, b, 2.0, 2.0);
        assertNear(0.0, check.calculateRemainingResource(
                        Collections.singletonList(zeroAllocation), 10.0), 1.0e-12,
                "zero tail slope has a finite constant infimum of zero");

        TrafficFlow negativeSlope = new TrafficFlow(49, a, b, 10.0 + 4.0e-9, 1.0, 5.0);
        FlowHopAllocation negativeAllocation = new FlowHopAllocation(negativeSlope, a, b, 2.0, 2.0);
        double phi = check.calculateRemainingResource(
                Collections.singletonList(negativeAllocation), 10.0);
        assertTrue(phi == Double.NEGATIVE_INFINITY,
                "any negative tail slope, including one within the former EPSILON, has infimum -infinity; got " + phi);
    }

    private static void testRouteBudgetBoundaries() {
        Fixture f = new Fixture(100.0);
        TrafficFlow equalTotal = f.flow(50, f.a, f.c, 0.0, 0.0, 2.0);
        List<PathDecision> equalRoute = Arrays.asList(
                new PathDecision(f.a, f.b, 1.0),
                new PathDecision(f.b, f.c, 1.0));
        assertTrue(f.cascade.checkRouteQualification(equalTotal, equalRoute),
                "sum of local budgets equal to end-to-end deadline must pass");

        TrafficFlow equalHopFloor = f.flow(51, f.a, f.b, 0.0, 0.0, 0.2);
        List<PathDecision> floorRoute = Collections.singletonList(
                new PathDecision(f.a, f.b, 0.2));
        String reason = f.cascade.getQualificationFailureReason(equalHopFloor, floorRoute);
        assertTrue(reason != null && reason.contains("HOP_DEADLINE_TOO_SMALL"),
                "budget equal to processing+propagation must fail the strict local constraint: " + reason);
    }

    private static void testEndpointAccessDelayDeadlineBoundaries() {
        Fixture f = new Fixture(10000.0);
        List<PathDecision> route = Arrays.asList(
                new PathDecision(f.a, f.b, 1.0),
                new PathDecision(f.b, f.c, 1.0));

        TrafficFlow zeroAccess = f.flow(61, f.a, f.c, 0.0, 0.0, 2.0);
        assertNear(0.0, zeroAccess.getTotalEndpointAccessDelayMs(), 0.0,
                "legacy flow construction must explicitly preserve zero endpoint delays");
        assertTrue(f.cascade.checkRouteQualification(zeroAccess, route),
                "zero endpoint delays retain the existing exact budget-equals-deadline pass case");

        TrafficFlow exactAccessBoundary = new TrafficFlow(
                62, f.a, f.c, 0.0, 0.0, 3.0, 0.4, 0.6);
        assertTrue(f.cascade.checkRouteQualification(exactAccessBoundary, route),
                "hop budgets plus source/destination access delays equal to D must pass Eq. (19f)");

        TrafficFlow overAccessBoundary = new TrafficFlow(
                63, f.a, f.c, 0.0, 0.0, 3.0, 0.5, 0.6);
        String reason = f.cascade.getQualificationFailureReason(overAccessBoundary, route);
        assertTrue(reason != null && reason.contains("END_TO_END_BUDGET_EXCEEDED")
                        && reason.contains("source access 0.500000 ms")
                        && reason.contains("destination access 0.600000 ms"),
                "nonzero endpoint delay must make a 3.1ms total requirement fail a 3ms deadline: " + reason);
    }

    private static void testEndpointDelayFailureDoesNotReserveRoute() {
        Router a = new Router(64, "ACCESS-ROLLBACK-A");
        Router b = new Router(65, "ACCESS-ROLLBACK-B");
        Router c = new Router(66, "ACCESS-ROLLBACK-C");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a); topology.addRouter(b); topology.addRouter(c);
        topology.addLink(new Link(a, b, 0.1, 10000.0));
        topology.addLink(new Link(b, c, 0.1, 10000.0));
        RouteCascade cascade = new RouteCascade(topology, 0.05, 0.0);
        TrafficFlow flow = new TrafficFlow(64, a, c, 0.0, 0.0,
                2.5, 0.3, 0.3);
        List<PathDecision> route = Arrays.asList(
                new PathDecision(a, b, 1.0),
                new PathDecision(b, c, 1.0));

        assertTrue(new DeadlineSatisfiability(0.0).isSatisfied(
                        Collections.singletonList(new FlowHopAllocation(flow, a, b, 1.0, 1.0)),
                        10000.0),
                "first link independently passes its resource check");
        String reason = cascade.getQualificationFailureReason(flow, route);
        assertTrue(reason != null && reason.contains("END_TO_END_BUDGET_EXCEEDED"),
                "failure should occur at the end-to-end check after both hop checks: " + reason);
        assertFalse(cascade.admitRoute(flow, route),
                "route must be rejected when endpoint delays push the total past D");
        assertTrue(cascade.getAdmittedAllocations().isEmpty(),
                "end-to-end failure must leave no partial route reservations");
    }

    private static void testReservationRollbackOnLaterHopFailure() {
        Router a = new Router(52, "ROLLBACK-A");
        Router b = new Router(53, "ROLLBACK-B");
        Router c = new Router(54, "ROLLBACK-C");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a); topology.addRouter(b); topology.addRouter(c);
        topology.addLink(new Link(a, b, 0.1, 100.0));
        topology.addLink(new Link(b, c, 0.1, 10.0));
        RouteCascade cascade = new RouteCascade(topology, 0.1, 1500.0);
        TrafficFlow flow = new TrafficFlow(52, a, c, 0.0, 2.0, 4.0);
        List<PathDecision> route = Arrays.asList(
                new PathDecision(a, b, 2.0),
                new PathDecision(b, c, 2.0));
        FlowHopAllocation firstHop = new FlowHopAllocation(flow, a, b, 2.0, 2.0);
        assertTrue(new DeadlineSatisfiability(1500.0)
                        .isSatisfied(Collections.singletonList(firstHop), 100.0),
                "first link must pass independently");
        String reason = cascade.getQualificationFailureReason(flow, route);
        assertTrue(reason != null && reason.contains("EQ17_DEADLINE_SATISFIABILITY_FAILED on ROLLBACK-B -> ROLLBACK-C"),
                "later link should be the first failing qualification: " + reason);
        assertFalse(cascade.admitRoute(flow, route), "multihop route must be rejected");
        assertTrue(cascade.getAdmittedAllocations().isEmpty(),
                "failure on a later hop must leave no partial reservations");
    }

    private static void testReverseDirectionReservationsAreIndependent() {
        Router a = new Router(55, "DIR-A");
        Router b = new Router(56, "DIR-B");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a); topology.addRouter(b);
        topology.addLink(new Link(a, b, 0.1, 100.0));
        RouteCascade cascade = new RouteCascade(topology, 0.1, 0.0);
        TrafficFlow forward = new TrafficFlow(55, a, b, 60.0, 0.0, 2.0);
        TrafficFlow reverse = new TrafficFlow(56, b, a, 60.0, 0.0, 2.0);
        assertTrue(cascade.admitRoute(forward,
                        Collections.singletonList(new PathDecision(a, b, 2.0))),
                "forward flow should be admitted");
        assertTrue(cascade.admitRoute(reverse,
                        Collections.singletonList(new PathDecision(b, a, 2.0))),
                "reverse flow must use independent directed-link capacity");
        assertTrue(cascade.getAdmittedAllocations().size() == 2,
                "both independent directed reservations should be retained");
    }

    private static void testRepeatedFlowIdReplacementBehavior() {
        Router a = new Router(57, "REPLACE-A");
        Router b = new Router(58, "REPLACE-B");
        Router c = new Router(59, "REPLACE-C");
        Router d = new Router(60, "REPLACE-D");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a); topology.addRouter(b);
        topology.addRouter(c); topology.addRouter(d);
        topology.addLink(new Link(a, b, 0.1, 100.0));
        topology.addLink(new Link(c, d, 0.1, 100.0));
        RouteCascade cascade = new RouteCascade(topology, 0.1, 0.0);
        TrafficFlow original = new TrafficFlow(57, a, b, 1.0, 0.0, 2.0);
        TrafficFlow replacement = new TrafficFlow(57, c, d, 1.0, 0.0, 2.0);
        assertTrue(cascade.admitRoute(original,
                        Collections.singletonList(new PathDecision(a, b, 2.0))),
                "original flow ID should be admitted");
        assertTrue(cascade.admitRoute(replacement,
                        Collections.singletonList(new PathDecision(c, d, 2.0))),
                "re-admission with the same ID should succeed");
        List<FlowHopAllocation> allocations = cascade.getAdmittedAllocations();
        assertTrue(allocations.size() == 1
                        && allocations.get(0).usesLink(c, d)
                        && allocations.get(0).getFlow() == replacement,
                "successful same-ID admission replaces the old route's reservation");

        assertFalse(cascade.admitRoute(replacement,
                        Collections.singletonList(new PathDecision(c, d, 0.2))),
                "a rejected replacement must not commit");
        allocations = cascade.getAdmittedAllocations();
        assertTrue(allocations.size() == 1
                        && allocations.get(0).usesLink(c, d)
                        && allocations.get(0).getFlow() == replacement,
                "failed same-ID replacement leaves the previously committed reservation intact");
    }

    private static class Fixture {
        final Router a = new Router(1, "A");
        final Router b = new Router(2, "B");
        final Router c = new Router(3, "C");
        final Router d = new Router(4, "D");
        final NetworkTopology topology = new NetworkTopology();
        final RouteCascade cascade;

        Fixture(double capacityMbps) {
            topology.addRouter(a);
            topology.addRouter(b);
            topology.addRouter(c);
            topology.addRouter(d);
            topology.addLink(new Link(a, b, 0.1, capacityMbps));
            topology.addLink(new Link(b, c, 0.1, capacityMbps));
            topology.addLink(new Link(c, d, 0.1, capacityMbps));
            cascade = new RouteCascade(topology, 0.1);
        }

        TrafficFlow flow(int id, Router source, Router destination,
                         double rateMbps, double burstKB, double deadlineMs) {
            return new TrafficFlow(id, source, destination,
                    rateMbps, burstKB, deadlineMs);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertNear(double expected, double actual,
                                   double tolerance, String message) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
