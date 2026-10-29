import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.DeadlineSatisfiability;
import routing.GlobalStateEncoder;
import routing.MAPPOEpisode;
import routing.MAPPOEpisodeResult;
import routing.MAPPOActor;
import routing.MAPPOTrainer;
import routing.MAPPORequestEpisodeDriver;
import routing.NetworkState;
import routing.PathDecision;
import routing.RouteCascade;
import routing.TrajectoryStep;
import traffic.TrafficFlow;

public class MAPPOSharedRewardTest {
    public static void main(String[] args) {
        testAdmittedFlowSharesRewardAcrossAgentSteps();
        testRejectedFlowSharesZeroRewardAcrossAgentSteps();
        testFlowRequestStateAndTerminalSequenceIncludesInactiveAgents();
        testRequestDriverCollectsContinuousTransitions();
        testLoopCannotPassAdmission();
        System.out.println("MAPPOSharedRewardTest passed (state timing, shared outcomes, active/inactive agents, terminal boundaries, invalid route).");
    }

    private static void testAdmittedFlowSharesRewardAcrossAgentSteps() {
        Fixture fixture = new Fixture(1000.0, 0.0);
        double[] stateBeforeRequest = fixture.state(fixture.flow(2, 80.0), Collections.<PathDecision>emptyList());
        int allocationsBefore = fixture.cascade.getAdmittedAllocations().size();
        MAPPOEpisodeResult result = fixture.run(2, 80.0);
        check(result.isRouteComplete() && result.isRouteQualified(),
                "valid two-hop request must be admitted");
        check(result.getTrajectory().size() == 3,
                "every router agent must receive one flow-request transition");
        assertSharedOutcome(result, fixture.trainer.getAdmissionReward(),
                TrajectoryStep.BoundaryType.TRUNCATED);
        assertStateTiming(result, stateBeforeRequest,
                fixture.stateEncoder().encodeNoPendingRequest(fixture.flow));
        check(fixture.cascade.getAdmittedAllocations().size() > allocationsBefore,
                "admitted next state must follow committed route reservations");
        check(activeFor(result, fixture.a) && activeFor(result, fixture.b)
                        && !activeFor(result, fixture.c),
                "both forwarding routers act; the destination router is inactive");
        assertActionIndicesMatchRoute(result, fixture.topology, fixture.flow, fixture.trainer);
        fixture.trainer.train(result.getTrajectory());
    }

    private static void testRejectedFlowSharesZeroRewardAcrossAgentSteps() {
        Fixture fixture = new Fixture(100.0, 0.0);
        TrafficFlow existing = new TrafficFlow(1, fixture.a, fixture.b, 80.0, 0.0, 5.0);
        check(fixture.cascade.admitRoute(existing, Collections.singletonList(
                        new PathDecision(fixture.a, fixture.b, 2.0))),
                "existing reservation should be accepted for the interference test");
        fixture.flow = fixture.flow(2, 80.0);
        double[] stateBeforeRequest = fixture.state(fixture.flow, Collections.<PathDecision>emptyList());
        int allocationsBefore = fixture.cascade.getAdmittedAllocations().size();
        MAPPOEpisodeResult result = fixture.run(2, 80.0);
        check(result.isRouteComplete() && !result.isRouteQualified(),
                "route rejected by Eq. (17) must remain complete but unadmitted");
        check(result.getRejectionReason().contains("EQ17_DEADLINE_SATISFIABILITY_FAILED"),
                "rejected flow must expose its resource failure reason");
        check(result.getTrajectory().size() == 3,
                "all routers, including inactive ones, must receive the request outcome");
        assertSharedOutcome(result, 0.0, TrajectoryStep.BoundaryType.TRUNCATED);
        assertStateTiming(result, stateBeforeRequest,
                fixture.stateEncoder().encodeNoPendingRequest(fixture.flow));
        check(fixture.cascade.getAdmittedAllocations().size() == allocationsBefore,
                "rejected next state must not include a partial reservation");
        check(activeFor(result, fixture.a) && activeFor(result, fixture.b)
                        && !activeFor(result, fixture.c),
                "rejected flow still records the routers that actually acted");
        assertActionIndicesMatchRoute(result, fixture.topology, fixture.flow, fixture.trainer);
        fixture.trainer.train(result.getTrajectory());
    }

    private static void testFlowRequestStateAndTerminalSequenceIncludesInactiveAgents() {
        Fixture fixture = new Fixture(1000.0, 0.0);
        TrafficFlow firstFlow = fixture.flow(10, 20.0);
        TrafficFlow secondFlow = new TrafficFlow(11, fixture.a, fixture.b,
                20.0, 0.0, 10.0);
        MAPPOEpisode episode = new MAPPOEpisode(
                fixture.topology, fixture.networkState, fixture.cascade, fixture.trainer, 5);
        MAPPOEpisodeResult first = episode.run(firstFlow,
                Collections.singletonList(firstFlow), Collections.<Integer, Double>emptyMap(),
                TrajectoryStep.BoundaryType.NONE, secondFlow);
        MAPPOEpisodeResult second = episode.run(secondFlow,
                Collections.singletonList(secondFlow), Collections.<Integer, Double>emptyMap(),
                TrajectoryStep.BoundaryType.TRUNCATED);

        check(first.getTrajectory().size() == 3 && second.getTrajectory().size() == 3,
                "one transition per router must be collected for each flow request");
        for (int i = 0; i < first.getTrajectory().size(); i++) {
            check(Arrays.equals(first.getTrajectory().get(i).getNextGlobalState(),
                            second.getTrajectory().get(i).getGlobalState()),
                    "nonterminal next state must equal the actual next request state after reservations");
        }
        double[] finalEnvironmentState = fixture.stateEncoder().encodeNoPendingRequest(secondFlow);
        for (TrajectoryStep step : second.getTrajectory().getSteps()) {
            check(Arrays.equals(step.getNextGlobalState(), finalEnvironmentState),
                    "truncated bootstrap state must retain the final network state without stale request fields");
        }
        check(first.getTrajectory().get(0).getBoundaryType() == TrajectoryStep.BoundaryType.NONE
                        && second.getTrajectory().get(0).isTruncated(),
                "truncation belongs to the rollout horizon, not each route end");
        routing.TrajectoryBuffer combined = new routing.TrajectoryBuffer();
        combined.getSteps().addAll(first.getTrajectory().getSteps());
        combined.getSteps().addAll(second.getTrajectory().getSteps());
        Map<Integer, routing.TrajectoryBuffer> byAgent = combined.getAgentTrajectories();
        check(byAgent.size() == 3, "all routers must have an isolated time sequence");
        for (routing.TrajectoryBuffer agentSteps : byAgent.values()) {
            check(agentSteps.size() == 2,
                    "inactive routers must not create gaps in per-agent GAE time steps");
            check(agentSteps.get(0).getBoundaryType() == TrajectoryStep.BoundaryType.NONE
                            && agentSteps.get(1).isTruncated(),
                    "each agent sequence must truncate on the same final request");
            check(agentSteps.get(0).getNextGlobalState().length
                            == agentSteps.get(1).getGlobalState().length,
                    "successive flow transitions must have consistent state dimensions");
        }
        TrajectoryStep routerBOnSecond = stepFor(second, fixture.b);
        check(!routerBOnSecond.isActive(),
                "router B is the destination in the second request and did not act");
        check(routerBOnSecond.getActionIndex() == -1
                        && routerBOnSecond.getActionProbability() == 0.0,
                "inactive sentinel must not masquerade as a selected action");

        boolean rejectedMissingSuccessor = false;
        try {
            episode.run(secondFlow, Collections.singletonList(secondFlow),
                    Collections.<Integer, Double>emptyMap(), TrajectoryStep.BoundaryType.NONE);
        } catch (IllegalArgumentException expected) {
            rejectedMissingSuccessor = true;
        }
        check(rejectedMissingSuccessor,
                "a continuing transition must not be created without its actual successor request");
    }

    private static void testLoopCannotPassAdmission() {
        Router a = new Router(1, "A");
        Router b = new Router(2, "B");
        Router c = new Router(3, "C");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a); topology.addRouter(b); topology.addRouter(c);
        topology.addLink(new Link(a, b, 0.1, 1000.0));
        topology.addLink(new Link(b, c, 0.1, 1000.0));
        TrafficFlow flow = new TrafficFlow(3, a, c, 1.0, 0.0, 10.0);
        RouteCascade cascade = new RouteCascade(topology, 0.05, 0.0);
        java.util.List<PathDecision> loop = Arrays.asList(
                new PathDecision(a, b, 2.0),
                new PathDecision(b, a, 2.0),
                new PathDecision(a, c, 2.0));
        check(!cascade.checkRouteQualification(flow, loop)
                        && !cascade.admitRoute(flow, loop),
                "an invalid loop action sequence cannot be treated as a valid admission");
    }

    private static void testRequestDriverCollectsContinuousTransitions() {
        Fixture fixture = new Fixture(1000.0, 0.0);
        CapturingTrainer trainer = new CapturingTrainer(fixture.topology,
                fixture.trainer.getCriticInputSize());
        TrafficFlow first = fixture.flow(30, 20.0);
        TrafficFlow second = new TrafficFlow(31, fixture.a, fixture.b,
                20.0, 0.0, 10.0);
        MAPPORequestEpisodeDriver driver = new MAPPORequestEpisodeDriver(
                fixture.topology, fixture.networkState, fixture.cascade, trainer, 5);
        driver.train(Arrays.asList(first, second), 1, 2, 1, 1);

        routing.TrajectoryBuffer captured = trainer.capturedRollout;
        check(captured != null && captured.size() == fixture.topology.getRouterCount() * 2,
                "request driver must submit one transition per router and request");
        Map<Integer, routing.TrajectoryBuffer> byAgent = captured.getAgentTrajectories();
        for (routing.TrajectoryBuffer agentSteps : byAgent.values()) {
            check(agentSteps.size() == 2,
                    "each router must retain both request time steps, including inactive rows");
            check(Arrays.equals(agentSteps.get(0).getNextGlobalState(),
                            agentSteps.get(1).getGlobalState()),
                    "driver rollout must not bootstrap between different request states");
            check(agentSteps.get(1).isTruncated(),
                    "only the final request must carry the rollout truncation boundary");
        }
    }

    private static void assertSharedOutcome(MAPPOEpisodeResult result, double reward,
                                            TrajectoryStep.BoundaryType boundaryType) {
        for (int i = 0; i < result.getTrajectory().size(); i++) {
            TrajectoryStep step = result.getTrajectory().get(i);
            check(Math.abs(step.getReward() - reward) < 1.0e-12,
                    "every router-agent step must receive the shared admission outcome");
            check(step.getBoundaryType() == boundaryType,
                    "all agents must share the flow-request rollout boundary marker");
            check(step.getGlobalState().length == step.getNextGlobalState().length,
                    "critic current/next global-state dimensions must agree");
            boolean[] mask = step.getActionMask();
            check(mask != null && step.getObservation().length > 0,
                    "each agent transition must preserve a dimensioned observation and mask");
            if (!step.isActive()) {
                check(step.getActionIndex() == -1 && step.getActionProbability() == 0.0,
                        "inactive agent must carry no selected action or policy probability");
                for (boolean valid : mask) {
                    check(!valid, "inactive agent mask must disable every action");
                }
                for (double value : step.getObservation()) {
                    check(value == 0.0, "inactive observation placeholder must be zero-filled");
                }
            }
            for (int j = 0; j < result.getTrajectory().size(); j++) {
                check(Arrays.equals(step.getGlobalState(),
                                result.getTrajectory().get(j).getGlobalState())
                                && Arrays.equals(step.getNextGlobalState(),
                                result.getTrajectory().get(j).getNextGlobalState()),
                        "all agents must share the same pre-request and post-outcome states");
            }
        }
    }

    private static void assertStateTiming(MAPPOEpisodeResult result,
                                          double[] expectedBefore,
                                          double[] expectedAfter) {
        for (TrajectoryStep step : result.getTrajectory().getSteps()) {
            check(Arrays.equals(step.getGlobalState(), expectedBefore),
                    "current state must be captured before any router's route decision");
            check(Arrays.equals(step.getNextGlobalState(), expectedAfter),
                    "next state must be captured after qualification/reservation outcome");
        }
    }

    private static boolean activeFor(MAPPOEpisodeResult result, Router router) {
        for (TrajectoryStep step : result.getTrajectory().getSteps()) {
            if (step.getAgentId() == router.getId()) return step.isActive();
        }
        throw new AssertionError("missing router transition " + router.getName());
    }

    private static TrajectoryStep stepFor(MAPPOEpisodeResult result, Router router) {
        for (TrajectoryStep step : result.getTrajectory().getSteps()) {
            if (step.getAgentId() == router.getId()) return step;
        }
        throw new AssertionError("missing router transition " + router.getName());
    }

    private static void assertActionIndicesMatchRoute(
            MAPPOEpisodeResult result, NetworkTopology topology, TrafficFlow flow,
            MAPPOTrainer trainer) {
        int activeCount = 0;
        for (int i = 0; i < result.getTrajectory().size(); i++) {
            TrajectoryStep step = result.getTrajectory().get(i);
            Router actor = routerById(topology, step.getAgentId());
            MAPPOActor actorModel = trainer.getActor(actor);
            check(step.getObservation().length == actorModel.getObservationInputSize(),
                    "router's actor observation dimension must remain fixed");
            boolean[] mask = step.getActionMask();
            check(mask != null && mask.length == actorModel.getActionCount(),
                    "router's action-mask dimension must remain fixed");
            if (!step.isActive()) {
                for (boolean valid : mask) {
                    check(!valid, "inactive agent mask must disable every action");
                }
                continue;
            }
            PathDecision decision = null;
            for (PathDecision candidate : result.getPathDecisions()) {
                if (candidate.getRouter().getId() == actor.getId()) {
                    decision = candidate;
                    break;
                }
            }
            check(decision != null, "acting router must have one route decision");
            activeCount++;
            java.util.List<routing.BudgetAction> actions = routing.ActionSpace.getActions(
                    actor, flow, topology, 5);
            check(step.getActionIndex() < actions.size(), "sampled action index must exist");
            routing.BudgetAction selected = actions.get(step.getActionIndex());
            check(mask != null && mask[step.getActionIndex()],
                    "sampled route action must be enabled in its stored mask");
            check(selected.getNextHop().equals(decision.getNextHop())
                            && Math.abs(selected.getDeadlineBudget() - decision.getDeadlineBudget()) < 1.0e-12,
                    "the sampled neighbor/budget pair must equal the route decision: agent="
                            + actor.getName() + " action=" + step.getActionIndex()
                            + " selected=" + selected + " decision=" + decision);
        }
        check(activeCount == result.getPathDecisions().size(),
                "each route decision must have exactly one active agent transition");
    }

    private static Router routerById(NetworkTopology topology, int id) {
        for (Router router : topology.getRouters()) if (router.getId() == id) return router;
        throw new AssertionError("missing router " + id);
    }

    private static final class Fixture {
        final Router a = new Router(1, "A");
        final Router b = new Router(2, "B");
        final Router c = new Router(3, "C");
        final NetworkTopology topology = new NetworkTopology();
        final NetworkState networkState;
        final RouteCascade cascade;
        final MAPPOTrainer trainer;
        TrafficFlow flow;

        Fixture(double capacityMbps, double maxBlockingBytes) {
            topology.addRouter(a); topology.addRouter(b); topology.addRouter(c);
            topology.addLink(new Link(a, b, 0.1, capacityMbps));
            topology.addLink(new Link(b, c, 0.1, capacityMbps));
            networkState = new NetworkState(topology, 0.05, maxBlockingBytes);
            cascade = new RouteCascade(topology, 0.05, maxBlockingBytes);
            int criticSize = new GlobalStateEncoder(topology, networkState, cascade).getStateSize();
            trainer = new MAPPOTrainer(topology, 5, criticSize,
                    0.99, 0.95, 0.2, 0.001, 1.0, 42L);
        }

        MAPPOEpisodeResult run(int flowId, double rateMbps) {
            flow = flow(flowId, rateMbps);
            return new MAPPOEpisode(topology, networkState, cascade, trainer, 5)
                    .run(flow, Collections.singletonList(flow), Collections.emptyMap());
        }

        TrafficFlow flow(int flowId, double rateMbps) {
            return new TrafficFlow(flowId, a, c, rateMbps, 0.0, 10.0);
        }

        double[] state(TrafficFlow flow, java.util.List<PathDecision> decisions) {
            return new GlobalStateEncoder(topology, networkState, cascade).encode(flow, decisions);
        }

        GlobalStateEncoder stateEncoder() {
            return new GlobalStateEncoder(topology, networkState, cascade);
        }
    }

    private static final class CapturingTrainer extends MAPPOTrainer {
        routing.TrajectoryBuffer capturedRollout;

        CapturingTrainer(NetworkTopology topology, int criticInputSize) {
            super(topology, 5, criticInputSize, 0.99, 0.95,
                    0.2, 0.001, 1.0, 73L);
        }

        @Override
        public TrainingStats train(routing.TrajectoryBuffer trajectory,
                                   int minibatchSize, int updateRounds) {
            capturedRollout = new routing.TrajectoryBuffer();
            capturedRollout.getSteps().addAll(trajectory.getSteps());
            return super.train(trajectory, minibatchSize, updateRounds);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
