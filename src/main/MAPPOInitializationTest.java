import java.util.Arrays;

import network.Link;
import network.NetworkTopology;
import network.Router;
import routing.MAPPOActor;
import routing.MAPPOTrainer;

public class MAPPOInitializationTest {
    public static void main(String[] args) {
        NetworkTopology topology = new NetworkTopology();
        Router a = new Router(1, "A");
        Router b = new Router(2, "B");
        Router c = new Router(3, "C");
        Router d = new Router(4, "D");
        for (Router router : Arrays.asList(a, b, c, d)) topology.addRouter(router);
        topology.addLink(new Link(a, c, 1.0, 1000.0));
        topology.addLink(new Link(a, d, 1.0, 1000.0));
        topology.addLink(new Link(b, c, 1.0, 1000.0));
        topology.addLink(new Link(b, d, 1.0, 1000.0));

        MAPPOTrainer first = trainer(topology, 42L);
        MAPPOTrainer repeat = trainer(topology, 42L);
        MAPPOTrainer changed = trainer(topology, 43L);
        double[] state = new double[first.getCriticInputSize()];
        Arrays.fill(state, 0.75);
        check(first.getCritic().getValue(state) == repeat.getCritic().getValue(state),
                "same experiment seed must reproduce critic initialization");
        check(first.getCritic().getValue(state) != changed.getCritic().getValue(state),
                "different experiment seed must change critic initialization");

        double[] observation = new double[first.getActor(a).getObservationInputSize()];
        Arrays.fill(observation, 1.0);
        boolean[] maskA = new boolean[first.getActor(a).getActionCount()];
        Arrays.fill(maskA, true);
        double[] actorA = first.getActor(a).getProbabilitiesFromObservation(observation, maskA);
        double[] actorARepeat = repeat.getActor(a).getProbabilitiesFromObservation(observation, maskA);
        double[] actorSeeded = changed.getActor(a).getProbabilitiesFromObservation(observation, maskA);
        boolean[] maskB = new boolean[first.getActor(b).getActionCount()];
        Arrays.fill(maskB, true);
        double[] actorB = first.getActor(b).getProbabilitiesFromObservation(observation, maskB);

        check(Arrays.equals(actorA, actorARepeat),
                "same seed and router must reproduce actor initialization");
        check(!Arrays.equals(actorA, actorSeeded),
                "different experiment seeds must not reuse identical actor weights");
        check(!Arrays.equals(actorA, actorB),
                "same-shaped router actors must receive distinct deterministic initial weights");
        System.out.println("MAPPOInitializationTest passed: actor and critic seed assertions.");
    }

    private static MAPPOTrainer trainer(NetworkTopology topology, long seed) {
        return new MAPPOTrainer(topology, 4, 12,
                0.99, 0.95, 0.2, 0.001, 1.0, seed);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
