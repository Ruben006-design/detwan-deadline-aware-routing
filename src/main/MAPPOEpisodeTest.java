import java.util.*;

import network.*;
import routing.*;
import traffic.*;

public class MAPPOEpisodeTest {

    public static void main(String[] args) {

        NetworkTopology topology =
                new NetworkTopology();

        Router r1 = new Router(1, "R1");
        Router r2 = new Router(2, "R2");
        Router r3 = new Router(3, "R3");
        Router r4 = new Router(4, "R4");

        topology.addRouter(r1);
        topology.addRouter(r2);
        topology.addRouter(r3);
        topology.addRouter(r4);

        topology.addLink(
                new Link(r1, r2, 2.0, 10000));

        topology.addLink(
                new Link(r2, r3, 2.0, 10000));

        topology.addLink(
                new Link(r3, r4, 2.0, 10000));

        TrafficFlow flow =
                new TrafficFlow(
                        1,
                        r1,
                        r4,
                        20,
                        50,
                        10);

        List<TrafficFlow> activeFlows =
                new ArrayList<>();

        activeFlows.add(flow);

        Map<Integer, Double> allocatedBudgets =
                new HashMap<>();

        int budgetLevels = 5;
        double maxBlockingPacketBytes =
                DeadlineSatisfiability.DEFAULT_MAX_BLOCKING_PACKET_BYTES;

        NetworkState networkState =
                new NetworkState(
                        topology,
                        0.05,
                        maxBlockingPacketBytes);

        RouteCascade routeCascade =
                new RouteCascade(
                        topology,
                        0.05,
                        maxBlockingPacketBytes);

        int V =
                topology.getRouterCount();

        int criticInputSize = new GlobalStateEncoder(
                topology, networkState, routeCascade).getStateSize();

        MAPPOTrainer trainer =
                new MAPPOTrainer(
                        topology,
                        budgetLevels,
                        criticInputSize,
                        0.99,
                        0.95,
                        0.2,
                        0.001,
                        1.0);

        MAPPOEpisode episode =
                new MAPPOEpisode(
                        topology,
                        networkState,
                        routeCascade,
                        trainer,
                        budgetLevels);

        MAPPOEpisodeResult result =
                episode.run(
                        flow,
                        activeFlows,
                        allocatedBudgets);

        TrajectoryBuffer trajectory =
                result.getTrajectory();

        System.out.println(
                "Route complete = "
                + result.isRouteComplete());

        System.out.println(
                "Route qualified = "
                + result.isRouteQualified());

        System.out.println(
                "Total reward = "
                + result.getTotalReward());

        System.out.println(
                "Trajectory size = "
                + trajectory.size());

        for (int i = 0;
             i < trajectory.size();
             i++) {

            TrajectoryStep step =
                    trajectory.get(i);

            System.out.println(
                    "Step " + (i + 1)
                    + " | Agent = R"
                    + step.getAgentId()
                    + " | Action = "
                    + step.getActionIndex()
                    + " | Probability = "
                    + step.getActionProbability()
                    + " | Reward = "
                    + step.getReward());
        }
    }
}
