
import network.*;
import routing.*;
import traffic.*;

import java.util.List;

public class ActionSpaceTest {

    public static void main(String[] args) {

        NetworkTopology topology =
                new NetworkTopology();

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

        TrafficFlow flow =
                new TrafficFlow(
                        1,
                        r1,
                        r3,
                        20,
                        50,
                        10);

        List<BudgetAction> actions =
                ActionSpace.getActions(
                        r1,
                        flow,
                        topology,
                        5);

        System.out.println(
                "Number of actions: "
                + actions.size());

        for (BudgetAction action : actions) {
            System.out.println(action);
        }
    }
}