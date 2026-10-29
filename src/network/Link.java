package network;
public class Link {

    private Router source;
    private Router destination;
    private double propagationDelay;
    private double bandwidth;

    public Link(Router source, Router destination,
                double propagationDelay, double bandwidth) {

        this.source = source;
        this.destination = destination;
        this.propagationDelay = propagationDelay;
        this.bandwidth = bandwidth;
    }

    public Router getSource() {
        return source;
    }

    public Router getDestination() {
        return destination;
    }

    public double getPropagationDelay() {
        return propagationDelay;
    }

    public double getBandwidth() {
        return bandwidth;
    }
}
