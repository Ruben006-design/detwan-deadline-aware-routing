import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.DeadlineSatisfiability;
import routing.FlowHopAllocation;
import routing.NetworkState;
import routing.PathDecision;
import routing.RouteCascade;
import traffic.TrafficFlow;

/** Focused tests for command-line experiment configuration. */
public final class MainOptionsTest {
    public static void main(String[] args) throws Exception {
        testBlockingPacketDefault();
        testExplicitBlockingPacketSize();
        testExplicitValueReachesAdmissionAndObservation();
        testInvalidBlockingPacketSizes();
        testBudgetLevelsDefault();
        testExplicitBudgetLevels();
        testInvalidBudgetLevels();
        System.out.println("All Main options tests passed.");
    }

    private static void testBlockingPacketDefault() throws Exception {
        Object options = parseOptions();
        assertEquals(DeadlineSatisfiability.DEFAULT_MAX_BLOCKING_PACKET_BYTES,
                getBlockingPacketBytes(options),
                "omitting the option must preserve the existing default");
    }

    private static void testExplicitBlockingPacketSize() throws Exception {
        Object options = parseOptions("--max-blocking-packet-bytes", "3000");
        assertEquals(3000.0, getBlockingPacketBytes(options),
                "the explicit whole-byte value should be stored in ExperimentOptions");
    }

    private static void testExplicitValueReachesAdmissionAndObservation() throws Exception {
        Object options = parseOptions("--max-blocking-packet-bytes", "3000");
        double configuredBytes = getBlockingPacketBytes(options);
        Router source = new Router(1, "options-source");
        Router destination = new Router(2, "options-destination");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(source);
        topology.addRouter(destination);
        topology.addLink(new Link(source, destination, 0.1, 100.0));
        TrafficFlow flow = new TrafficFlow(1, source, destination, 0.0, 10.0, 5.0);
        PathDecision hop = new PathDecision(source, destination, 1.0);

        RouteCascade cascade = new RouteCascade(topology, 0.1, configuredBytes);
        if (cascade.checkRouteQualification(flow, Collections.singletonList(hop))) {
            throw new AssertionError("the parsed 3000-byte value should affect Eq. (17) admission");
        }

        FlowHopAllocation allocation = new FlowHopAllocation(
                flow, source, destination, 1.0, 1.0);
        double remaining = new NetworkState(topology, 0.1, configuredBytes)
                .getNeighborStates(source, destination, Collections.singletonList(allocation))
                .get(0).getRemainingDeadlineResource();
        assertEquals(-0.5, remaining,
                "the same parsed value should affect the Eq. (20) observation");
    }

    private static void testInvalidBlockingPacketSizes() throws Exception {
        assertRejected("not-a-number");
        assertRejected("-1");
        assertRejected("NaN");
        assertRejected("Infinity");
        assertRejected("1.5");

        try {
            parseOptions("--max-blocking-packet-bytes");
            throw new AssertionError("missing blocking-packet value should be rejected");
        } catch (InvocationTargetException e) {
            assertInvalidOptionError(e.getCause());
        }
    }

    private static void testBudgetLevelsDefault() throws Exception {
        Object options = parseOptions();
        int budgetLevels = getBudgetLevels(options);
        if (budgetLevels != 16) {
            throw new AssertionError("Default budget levels must be 16 per Table II; got " + budgetLevels);
        }
    }

    private static void testExplicitBudgetLevels() throws Exception {
        Object options = parseOptions("--budget-levels", "5");
        int budgetLevels = getBudgetLevels(options);
        if (budgetLevels != 5) {
            throw new AssertionError("Explicit budget levels should be 5; got " + budgetLevels);
        }
    }

    private static void testInvalidBudgetLevels() throws Exception {
        try {
            parseOptions("--budget-levels", "0");
            throw new AssertionError("Zero budget levels must be rejected");
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof IllegalArgumentException)) {
                throw new AssertionError("Expected IllegalArgumentException for zero budget levels", e);
            }
        }

        try {
            parseOptions("--budget-levels", "-5");
            throw new AssertionError("Negative budget levels must be rejected");
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof IllegalArgumentException)) {
                throw new AssertionError("Expected IllegalArgumentException for negative budget levels", e);
            }
        }
    }

    private static void assertRejected(String value) throws Exception {
        try {
            parseOptions("--max-blocking-packet-bytes", value);
            throw new AssertionError("invalid blocking-packet value should be rejected: " + value);
        } catch (InvocationTargetException e) {
            assertInvalidOptionError(e.getCause());
        }
    }

    private static void assertInvalidOptionError(Throwable error) {
        if (!(error instanceof IllegalArgumentException)
                || error.getMessage() == null
                || !error.getMessage().contains("--max-blocking-packet-bytes")) {
            throw new AssertionError("expected a clear blocking-packet option error", error);
        }
    }

    private static Object parseOptions(String... args) throws Exception {
        Method parser = Main.class.getDeclaredMethod("parseOptions", String[].class);
        parser.setAccessible(true);
        return parser.invoke(null, (Object) args);
    }

    private static double getBlockingPacketBytes(Object options) throws Exception {
        Field field = options.getClass().getDeclaredField("maxBlockingPacketBytes");
        field.setAccessible(true);
        return field.getDouble(options);
    }

    private static int getBudgetLevels(Object options) throws Exception {
        Field field = options.getClass().getDeclaredField("budgetLevels");
        field.setAccessible(true);
        return field.getInt(options);
    }

    private static void assertEquals(double expected, double actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
