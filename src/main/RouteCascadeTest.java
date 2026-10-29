import java.util.ArrayList;
import java.util.List;

import network.*;
import routing.*;
import traffic.*;

public class RouteCascadeTest {

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

        List<BudgetAction> actions =
                new ArrayList<>();

        actions.add(new BudgetAction(r2, 4.0));
        actions.add(new BudgetAction(r3, 4.0));
        actions.add(new BudgetAction(r4, 4.0));

        RouteCascade cascade =
                new RouteCascade(
                        topology,
                        0.05);

        List<PathDecision> decisions =
                cascade.buildRoute(
                        flow,
                        actions);

        System.out.println("Route:");

        for (PathDecision decision : decisions) {
            System.out.println(decision);
        }

        System.out.println(
                "\nRoute Complete: " +
                cascade.isComplete(flow, decisions));

        System.out.println(
                "Route Qualified: " +
                cascade.checkRouteQualification(
                        flow,
                        decisions));
    }
}