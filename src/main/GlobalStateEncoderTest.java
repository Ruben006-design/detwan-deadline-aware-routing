import java.util.Collections;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.DeadlineSatisfiability;
import routing.GlobalStateEncoder;
import routing.NetworkState;
import routing.RouteCascade;
import routing.MAPPOCritic;
import routing.ObservationEncoder;
import traffic.TrafficFlow;

public class GlobalStateEncoderTest {
    public static void main(String[] args) {
        Router a = new Router(1, "A");
        Router b = new Router(2, "B");
        Router c = new Router(3, "C");
        NetworkTopology topology = new NetworkTopology();
        topology.addRouter(a);
        topology.addRouter(b);
        topology.addRouter(c);
        topology.addLink(new Link(a, b, 2.0, 100.0));
        topology.addLink(new Link(b, c, 3.0, 200.0));

        NetworkState networkState = new NetworkState(topology, 0.05,
                DeadlineSatisfiability.DEFAULT_MAX_BLOCKING_PACKET_BYTES);
        RouteCascade cascade = new RouteCascade(topology, 0.05,
                DeadlineSatisfiability.DEFAULT_MAX_BLOCKING_PACKET_BYTES);
        GlobalStateEncoder encoder = new GlobalStateEncoder(topology, networkState, cascade);
        TrafficFlow flow = new TrafficFlow(1, a, c, 20.0, 50.0, 10.0);

        double[] state = encoder.encode(flow, Collections.emptyList());
        int expected = 6 * 3 * 3 + 5 * 3 + 3;
        check(state.length == expected && state.length == encoder.getStateSize(),
                "critic input must use the fixed centralized-state dimension");
        MAPPOCritic critic = new MAPPOCritic(encoder.getStateSize(), 42L);
        check(Double.isFinite(critic.getValue(state)),
                "critic must accept the encoded centralized global state");
        int actorInputSize = new ObservationEncoder(topology, 5)
                .getActorInputSize(topology.getLinks(a).size());
        check(actorInputSize != state.length,
                "global critic state must remain distinct from the local actor observation");
        boolean rejectedLocalState = false;
        try {
            critic.getValue(new double[actorInputSize]);
        } catch (IllegalArgumentException expectedDimensionFailure) {
            rejectedLocalState = true;
        }
        check(rejectedLocalState,
                "critic must reject a local actor vector in place of the global state");
        check(state[0 * 3 + 1] == 1.0 && state[3] == 1.0,
                "global state must include directed adjacency entries");
        check(state[18 + 0 * 3 + 1] == 2.0,
                "global state must include actual propagation delay");
        check(state[18 + 1 * 3 + 2] == 3.0,
                "global state must include every directed link's propagation delay");

        double[] anotherDecisionPoint = encoder.encode(flow, Collections.emptyList());
        check(java.util.Arrays.equals(state, anotherDecisionPoint),
                "critic state encoding must not depend on which router agent asks for it");
        System.out.println("GlobalStateEncoderTest passed: global state length=" + state.length);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
