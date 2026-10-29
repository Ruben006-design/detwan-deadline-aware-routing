import java.util.*;

import network.*;
import routing.*;
import traffic.*;

public class MAPPOActorTest {

    public static void main(String[] args) {

        NetworkTopology topology = new NetworkTopology();

        Router r1 = new Router(1, "R1");
        Router r2 = new Router(2, "R2");
        Router r3 = new Router(3, "R3");

        topology.addRouter(r1);
        topology.addRouter(r2);
        topology.addRouter(r3);

        topology.addLink(
                new Link(r1, r2, 2.0, 10000));

        topology.addLink(
                new Link(r1, r3, 2.0, 10000));

        topology.addLink(
                new Link(r2, r3, 2.0, 10000));

        TrafficFlow flow = new TrafficFlow(
                1,
                r1,
                r3,
                20,
                50,
                10);

        List<PathDecision> previousDecisions =
                new ArrayList<>();

        List<RouterObservation.NeighborState> neighbors =
                new ArrayList<>();

        neighbors.add(
                new RouterObservation.NeighborState(
                        r2,
                        2.0,
                        0.05,
                        1,
                        9000));

        neighbors.add(
                new RouterObservation.NeighborState(
                        r3,
                        2.0,
                        0.05,
                        0,
                        9000));

        RouterObservation observation =
                new RouterObservation(
                        flow,
                        previousDecisions,
                        neighbors);

        MAPPOActor actor =
                new MAPPOActor(
                        topology,
                        r1,
                        5);

        double[] probabilities =
                actor.getProbabilities(
                        observation);

        System.out.println(
                "Number of actions: " +
                probabilities.length);

        System.out.println(
                "\nAction probabilities:");

        for (int i = 0; i < probabilities.length; i++) {
            System.out.println(
                    "Action " + (i + 1) +
                    " = " + probabilities[i]);
        }

        BudgetAction action =
                actor.selectAction(
                        observation);

        System.out.println(
                "\nSelected Action:");

        System.out.println(action);
    }
}
