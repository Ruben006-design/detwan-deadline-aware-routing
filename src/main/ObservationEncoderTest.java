import java.util.*;

import network.*;
import routing.*;
import traffic.*;

public class ObservationEncoderTest {

    public static void main(String[] args) {

        NetworkTopology topology = new NetworkTopology();

        Router r1 = new Router(1, "R1");
        Router r2 = new Router(2, "R2");
        Router r3 = new Router(3, "R3");

        topology.addRouter(r1);
        topology.addRouter(r2);
        topology.addRouter(r3);

        topology.addLink(new Link(r1, r2, 2.0, 10000));
        topology.addLink(new Link(r2, r3, 2.0, 10000));

        TrafficFlow flow = new TrafficFlow(
                1,
                r1,
                r3,
                20,
                50,
                10
        );

        List<PathDecision> previousDecisions = new ArrayList<>();

        previousDecisions.add(
                new PathDecision(r1, r2, 3.0)
        );

        List<RouterObservation.NeighborState> neighbors =
                new ArrayList<>();

        neighbors.add(
                new RouterObservation.NeighborState(
                        r2,
                        2.0,
                        0.05,
                        1,
                        9000
                )
        );

        RouterObservation observation =
                new RouterObservation(
                        flow,
                        previousDecisions,
                        neighbors
                );

        ObservationEncoder encoder =
                new ObservationEncoder(topology, 5);

        double[] vector =
                encoder.encodeActorObservation(observation);

        int V = topology.getRouterCount();
        int Ni = neighbors.size();

        int expectedSize =
                V * V
                + 2 * V * Ni
                + 4 * V
                + 3
                + 2 * Ni;

        if (Math.abs(vector[expectedSize - 2] - 0.05) > 1.0e-12
                || Math.abs(vector[expectedSize - 1] - 2.0) > 1.0e-12) {
            throw new AssertionError("actor observation must append actual processing and propagation delay");
        }

        System.out.println("Number of nodes: " + V);
        System.out.println("Number of neighbors: " + Ni);
        System.out.println("Expected input size: " + expectedSize);
        System.out.println("Actual input size: " + vector.length);
        if (expectedSize != vector.length) {
            throw new AssertionError("actor observation dimension mismatch");
        }
        System.out.println("Dimension correct: true; neighbor delays=(0.05ms, 2.0ms)");
    }
}
