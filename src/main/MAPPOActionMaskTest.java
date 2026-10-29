import java.util.*;

import network.*;
import routing.*;
import traffic.TrafficFlow;

public class MAPPOActionMaskTest {

    public static void main(String[] args) {
        testActionMaskAndNormalizedProbabilities();
        testPpoUsesStoredMask();
        testDifferentHopCountsAndLookahead();
        testInsufficientRemainingDeadline();
        testAlternativeFeasiblePathsRemainAvailable();
        testFeasibleBudgetCombinationsRemainAvailable();
        testEndpointAccessDelaysReduceFeasibleBudgetTotal();
        testVisitedLoopIsExcluded();
        testNoValidActionTerminatesWithReason();
        testFixedSeedSampling();
        System.out.println("MAPPOActionMaskTest passed.");
    }

    private static void testActionMaskAndNormalizedProbabilities() {
        Fixture fixture = new Fixture(true);
        Set<Router> visited = new HashSet<>(Arrays.asList(fixture.source, fixture.visitedNeighbor));
        boolean[] mask = ActionSpace.getActionMask(
                fixture.source, fixture.flow, fixture.topology, 5,
                visited, 4.0, 0.05);

        // Action order is R2 budgets 2..10ms, then R3 budgets 2..10ms.
        check(!mask[0] && !mask[1] && !mask[2] && !mask[3] && !mask[4],
                "visited next-hop actions must be masked");
        check(!mask[5] && mask[6] && mask[7] && !mask[8] && !mask[9],
                "hop delay and remaining end-to-end budget constraints must be masked");

        MAPPOActor actor = new MAPPOActor(fixture.topology, fixture.source, 5, 7L);
        double[] probabilities = actor.getProbabilities(fixture.observation(), mask);
        assertDistribution(probabilities, mask);

        BudgetAction selected = actor.selectAction(
                fixture.observation(), mask, new Random(19L));
        check(selected != null, "a valid action should be sampled when one exists");
        check(selected.getNextHop().equals(fixture.destination),
                "sampled action must not revisit the visited neighbor");
        check(selected.getDeadlineBudget() == 4.0 || selected.getDeadlineBudget() == 6.0,
                "sampled budget must satisfy both deadline constraints");

        boolean[] noActions = new boolean[mask.length];
        double[] noActionProbabilities = actor.getProbabilities(fixture.observation(), noActions);
        check(Arrays.stream(noActionProbabilities).sum() == 0.0,
                "all-false mask should produce no sampleable probability mass");
        check(actor.selectAction(fixture.observation(), noActions, new Random(1L)) == null,
                "all-false mask should return no action");
    }

    private static void testPpoUsesStoredMask() {
        Fixture fixture = new Fixture(true);
        boolean[] mask = ActionSpace.getActionMask(
                fixture.source, fixture.flow, fixture.topology, 5,
                new HashSet<>(Arrays.asList(fixture.source, fixture.visitedNeighbor)),
                4.0, 0.05);
        MAPPOActor actor = new MAPPOActor(fixture.topology, fixture.source, 5, 11L);
        double[] observation = new ObservationEncoder(fixture.topology, 5)
                .encodeActorObservation(fixture.observation());
        double[] before = actor.getProbabilitiesFromObservation(observation, mask);
        TrajectoryStep step = new TrajectoryStep(
                fixture.source.getId(), observation, observation,
                6, before[6], 0.0, observation, mask);

        PPOActorTrainer ppo = new PPOActorTrainer(0.2);
        ppo.update(actor, step, 1.0, 0.001);

        double[] after = actor.getProbabilitiesFromObservation(
                observation, step.getActionMask());
        assertDistribution(after, mask);
        check(step.getActionMask()[6], "chosen action must remain valid in stored PPO mask");
        boolean[] copiedMask = step.getActionMask();
        copiedMask[6] = false;
        check(step.getActionMask()[6], "trajectory mask getter must return a defensive copy");
    }

    private static void testNoValidActionTerminatesWithReason() {
        NetworkTopology topology = new NetworkTopology();
        Router source = new Router(1, "S");
        Router deadEnd = new Router(2, "X");
        Router destination = new Router(3, "D");
        topology.addRouter(source);
        topology.addRouter(deadEnd);
        topology.addRouter(destination);
        topology.addLink(new Link(source, deadEnd, 2.0, 10000.0));

        TrafficFlow flow = new TrafficFlow(5, source, destination, 1.0, 50.0, 10.0);
        NetworkState state = new NetworkState(topology, 0.05,
                DeadlineSatisfiability.DEFAULT_MAX_BLOCKING_PACKET_BYTES);
        RouteCascade cascade = new RouteCascade(topology, 0.05,
                DeadlineSatisfiability.DEFAULT_MAX_BLOCKING_PACKET_BYTES);
        int criticSize = new GlobalStateEncoder(topology, state, cascade).getStateSize();
        MAPPOTrainer trainer = new MAPPOTrainer(
                topology, 5, criticSize, 0.99, 0.95, 0.2, 0.001, 1.0, 23L);

        MAPPOEpisodeResult result = new MAPPOEpisode(
                topology, state, cascade, trainer, 5)
                .run(flow, Collections.singletonList(flow), new HashMap<>());

        check(!result.isRouteComplete(), "dead-end flow must remain incomplete");
        check(!result.isRouteQualified(), "dead-end flow must not be admitted");
        check(result.getTerminationReason().startsWith("NO_FEASIBLE_ROUTE"),
                "episode must report that no deadline-feasible route exists");
        check(result.getRejectionReason().equals(result.getTerminationReason()),
                "incomplete route rejection should preserve its termination reason");
    }

    private static void testDifferentHopCountsAndLookahead() {
        NetworkTopology topology = new NetworkTopology();
        Router source = new Router(1, "S");
        Router shortPath = new Router(2, "A");
        Router longPath = new Router(3, "B");
        Router middle = new Router(4, "C");
        Router destination = new Router(5, "D");
        for (Router router : Arrays.asList(source, shortPath, longPath, middle, destination)) {
            topology.addRouter(router);
        }
        topology.addLink(new Link(source, shortPath, 2.0, 10000.0));
        topology.addLink(new Link(shortPath, destination, 2.0, 10000.0));
        topology.addLink(new Link(source, longPath, 2.0, 10000.0));
        topology.addLink(new Link(longPath, middle, 2.0, 10000.0));
        topology.addLink(new Link(middle, destination, 2.0, 10000.0));
        TrafficFlow flow = new TrafficFlow(1, source, destination, 1.0, 50.0, 10.0);

        int shortHops = ActionSpace.getMinimumRemainingHops(
                shortPath, destination, topology, Collections.singleton(source));
        int longHops = ActionSpace.getMinimumRemainingHops(
                longPath, destination, topology, Collections.singleton(source));
        check(shortHops == 1 && longHops == 2,
                "lookahead must report different remaining hop counts");

        boolean[] mask = ActionSpace.getActionMask(source, flow, topology, 5,
                Collections.singleton(source), 0.0, 0.05);
        // S->A occupies actions 0..4; S->B occupies actions 5..9.
        check(mask[1] && mask[2], "the two-hop route should retain feasible budgets");
        for (int i = 5; i < 10; i++) {
            check(!mask[i], "the three-hop route must be masked when its minimum budget exceeds 10ms");
        }
    }

    private static void testInsufficientRemainingDeadline() {
        NetworkTopology topology = new NetworkTopology();
        Router source = new Router(1, "S");
        Router via = new Router(2, "A");
        Router destination = new Router(3, "D");
        for (Router router : Arrays.asList(source, via, destination)) topology.addRouter(router);
        topology.addLink(new Link(source, via, 2.0, 10000.0));
        topology.addLink(new Link(via, destination, 2.0, 10000.0));
        topology.addLink(new Link(source, destination, 2.0, 10000.0));
        TrafficFlow flow = new TrafficFlow(2, source, destination, 1.0, 50.0, 10.0);

        boolean[] mask = ActionSpace.getActionMask(source, flow, topology, 5,
                Collections.singleton(source), 4.0, 0.05);
        // The via-router action would require 4ms now plus 4ms minimum later,
        // while only 6ms remain. The direct destination action needs no later hop.
        check(!mask[1], "lookahead must reject a locally valid action with insufficient future budget");
        check(mask[6], "a direct destination action should remain available");
    }

    private static void testAlternativeFeasiblePathsRemainAvailable() {
        NetworkTopology topology = new NetworkTopology();
        Router source = new Router(1, "S");
        Router first = new Router(2, "A");
        Router second = new Router(3, "B");
        Router middle = new Router(4, "C");
        Router destination = new Router(5, "D");
        for (Router router : Arrays.asList(source, first, second, middle, destination)) {
            topology.addRouter(router);
        }
        topology.addLink(new Link(source, first, 2.0, 10000.0));
        topology.addLink(new Link(first, destination, 2.0, 10000.0));
        topology.addLink(new Link(source, second, 2.0, 10000.0));
        topology.addLink(new Link(second, middle, 2.0, 10000.0));
        topology.addLink(new Link(middle, destination, 2.0, 10000.0));
        TrafficFlow flow = new TrafficFlow(3, source, destination, 1.0, 50.0, 20.0);

        boolean[] mask = ActionSpace.getActionMask(source, flow, topology, 5,
                Collections.singleton(source), 0.0, 0.05);
        check(mask[1], "the shorter alternative should remain feasible");
        check(mask[6], "the longer alternative should remain feasible when the deadline allows it");
    }

    private static void testFeasibleBudgetCombinationsRemainAvailable() {
        NetworkTopology topology = new NetworkTopology();
        Router source = new Router(1, "S");
        Router middle = new Router(2, "A");
        Router destination = new Router(3, "D");
        for (Router router : Arrays.asList(source, middle, destination)) {
            topology.addRouter(router);
        }
        topology.addLink(new Link(source, middle, 2.0, 10000.0));
        topology.addLink(new Link(middle, destination, 2.0, 10000.0));
        TrafficFlow flow = new TrafficFlow(6, source, destination, 1.0, 50.0, 12.0);

        boolean[] firstHopMask = ActionSpace.getActionMask(
                source, flow, topology, 6, Collections.singleton(source), 0.0, 0.05);
        // Six budget levels give 2ms increments. The second hop needs at least
        // 4ms, so first-hop budgets 4, 6, and 8ms all retain a feasible split;
        // 10ms leaves too little for the final hop.
        check(!firstHopMask[0], "2ms must fail the strict processing-plus-propagation constraint");
        check(firstHopMask[1] && firstHopMask[2] && firstHopMask[3],
                "multiple first-hop budget choices should allow a feasible two-hop allocation");
        check(!firstHopMask[4] && !firstHopMask[5],
                "budgets that leave insufficient time for the final hop must be masked");

        boolean[] secondHopMask = ActionSpace.getActionMask(
                middle, flow, topology, 6,
                new HashSet<>(Arrays.asList(source, middle)), 6.0, 0.05);
        // The topology automatically adds a reverse edge to S, so A->D's
        // six budget slots follow the six masked A->S slots.
        check(!secondHopMask[6] && secondHopMask[7] && secondHopMask[8],
                "at 6ms accumulated, feasible final-hop discrete budgets must fit the 12ms deadline");
        check(!secondHopMask[9],
                "a final-hop budget above the remaining 6ms must be masked");
    }

    private static void testVisitedLoopIsExcluded() {
        NetworkTopology topology = new NetworkTopology();
        Router source = new Router(1, "S");
        Router current = new Router(2, "A");
        Router destination = new Router(3, "D");
        Router detour = new Router(4, "X");
        topology.addRouter(source);
        topology.addRouter(current);
        topology.addRouter(destination);
        topology.addRouter(detour);
        topology.addLink(new Link(source, current, 2.0, 10000.0));
        topology.addLink(new Link(current, destination, 2.0, 10000.0));
        topology.addLink(new Link(current, detour, 2.0, 10000.0));
        topology.addLink(new Link(detour, source, 2.0, 10000.0));
        TrafficFlow flow = new TrafficFlow(4, source, destination, 1.0, 50.0, 10.0);

        boolean[] mask = ActionSpace.getActionMask(current, flow, topology, 5,
                new HashSet<>(Arrays.asList(source, current)), 4.0, 0.05);
        for (int i = 0; i < 5; i++) {
            check(!mask[i], "reverse edge to a visited router must be masked");
        }
        check(mask[6], "unvisited destination must remain a valid alternative");
        for (int i = 10; i < 15; i++) {
            check(!mask[i], "a detour whose only continuation revisits a router must be masked");
        }
    }

    private static void testEndpointAccessDelaysReduceFeasibleBudgetTotal() {
        NetworkTopology topology = new NetworkTopology();
        Router source = new Router(20, "ACCESS-S");
        Router middle = new Router(21, "ACCESS-M");
        Router destination = new Router(22, "ACCESS-D");
        topology.addRouter(source);
        topology.addRouter(middle);
        topology.addRouter(destination);
        topology.addLink(new Link(source, middle, 0.1, 10000.0));
        topology.addLink(new Link(middle, destination, 0.1, 10000.0));
        TrafficFlow flow = new TrafficFlow(20, source, destination,
                1.0, 0.0, 5.0, 1.0, 1.0);

        boolean[] mask = ActionSpace.getActionMask(source, flow, topology, 5,
                Collections.singleton(source), 0.0, 0.05);
        // Eq. (22) still discretizes D=5ms into 1ms actions; after the 2ms
        // endpoint contribution, only 3ms remains for the two hop budgets.
        check(mask[0] && mask[1], "1ms and 2ms first-hop budgets leave a feasible final hop");
        check(!mask[2] && !mask[3] && !mask[4],
                "first-hop budgets above 2ms must be masked by the remaining E2E deadline");
    }

    private static void testFixedSeedSampling() {
        NetworkTopology topology = new NetworkTopology();
        Router a = new Router(1, "A");
        Router b = new Router(2, "B");
        topology.addRouter(a);
        topology.addRouter(b);
        topology.addLink(new Link(a, b, 2.0, 10000.0));

        MAPPOTrainer first = trainer(topology, 9876L);
        MAPPOTrainer second = trainer(topology, 9876L);
        double[] probabilities = {0.0, 0.25, 0.0, 0.75};
        for (int i = 0; i < 20; i++) {
            check(first.sampleActionIndex(probabilities)
                            == second.sampleActionIndex(probabilities),
                    "same seed must reproduce sampled action indices");
        }
    }

    private static MAPPOTrainer trainer(NetworkTopology topology, long seed) {
        return new MAPPOTrainer(topology, 5, 256,
                0.99, 0.95, 0.2, 0.001, 1.0, seed);
    }

    private static void assertDistribution(double[] probabilities, boolean[] mask) {
        double sum = 0.0;
        for (int i = 0; i < probabilities.length; i++) {
            if (!mask[i]) {
                check(probabilities[i] == 0.0, "masked action must have zero probability");
            } else {
                sum += probabilities[i];
            }
        }
        check(Math.abs(sum - 1.0) < 1.0e-12,
                "valid action probabilities must normalize to one");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Fixture {
        final NetworkTopology topology = new NetworkTopology();
        final Router source = new Router(1, "R1");
        final Router visitedNeighbor = new Router(2, "R2");
        final Router destination = new Router(3, "R3");
        final TrafficFlow flow;

        Fixture(boolean addDestinationLink) {
            topology.addRouter(source);
            topology.addRouter(visitedNeighbor);
            topology.addRouter(destination);
            topology.addLink(new Link(source, visitedNeighbor, 2.0, 10000.0));
            if (addDestinationLink) {
                topology.addLink(new Link(source, destination, 2.0, 10000.0));
            }
            flow = new TrafficFlow(1, source, destination, 20.0, 50.0, 10.0);
        }

        RouterObservation observation() {
            List<RouterObservation.NeighborState> neighbors = new ArrayList<>();
            for (Link link : topology.getLinks(source)) {
                neighbors.add(new RouterObservation.NeighborState(
                        link.getDestination(), link.getPropagationDelay(),
                        0.05, 1, 0.0));
            }
            return new RouterObservation(flow, Collections.emptyList(), neighbors);
        }
    }
}
