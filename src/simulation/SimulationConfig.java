package simulation;

public class SimulationConfig {

    private double deadline;
    private double burstSize;
    private double processingDelay;
    private double propagationDelay;

    public SimulationConfig(double deadline, double burstSize,
                            double processingDelay, double propagationDelay) {
        this.deadline = deadline;
        this.burstSize = burstSize;
        this.processingDelay = processingDelay;
        this.propagationDelay = propagationDelay;
    }

    public double getDeadline() {
        return deadline;
    }

    public double getBurstSize() {
        return burstSize;
    }

    public double getProcessingDelay() {
        return processingDelay;
    }

    public double getPropagationDelay() {
        return propagationDelay;
    }
}
