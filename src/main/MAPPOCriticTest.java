import routing.MAPPOCritic;

public class MAPPOCriticTest {

    public static void main(String[] args) {

        int inputSize = 10;

        MAPPOCritic critic =
                new MAPPOCritic(inputSize);

        double[] state =
                new double[inputSize];

        for (int i = 0; i < state.length; i++) {
            state[i] = 0.5;
        }

        double before =
                critic.getValue(state);

        double target = 1.0;

        critic.train(
                state,
                target,
                0.001);

        double after =
                critic.getValue(state);

        MAPPOCritic sameSeed = new MAPPOCritic(inputSize, 77L);
        MAPPOCritic repeatedSeed = new MAPPOCritic(inputSize, 77L);
        MAPPOCritic differentSeed = new MAPPOCritic(inputSize, 78L);
        check(sameSeed.getValue(state) == repeatedSeed.getValue(state),
                "critic initialization must repeat exactly for a fixed seed");
        check(sameSeed.getValue(state) != differentSeed.getValue(state),
                "critic initialization must vary with the configured seed");

        System.out.println(
                "Value before update = " + before);

        System.out.println(
                "Target value = " + target);

        System.out.println(
                "Value after update = " + after);
        System.out.println("Seeded critic initialization assertions passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
