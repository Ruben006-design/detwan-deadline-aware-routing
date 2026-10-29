package network;
import java.util.*;

public class NetworkTopology {

    private Map<Router, List<Link>> network = new HashMap<>();

    public void addRouter(Router router) {
        network.putIfAbsent(router, new ArrayList<>());
    }
    public void addLink(Link link) {
        addDirectedLink(link);
        Link reverseLink = new Link(
            link.getDestination(),
            link.getSource(),
            link.getPropagationDelay(),
            link.getBandwidth()
        );
        addDirectedLink(reverseLink);
    }

    /** Adds only the supplied directed link. Use when the model has asymmetric or one-way connectivity. */
    public void addDirectedLink(Link link) {
        if (link == null || !network.containsKey(link.getSource())
                || !network.containsKey(link.getDestination())) {
            throw new IllegalArgumentException("Both endpoint routers must be registered before adding a link");
        }
        network.get(link.getSource()).add(link);
}

    public List<Link> getLinks(Router router) {
        return network.get(router);
    }

    public int getRouterCount() {
        return network.size();
    }

    public int getLinkCount() {
        int count = 0;

        for (List<Link> links : network.values()) {
            count += links.size();
        }

        return count;
    }
public Router getRouter(String name) {
    for (Router router : network.keySet()) {
        if (router.getName().equals(name)) return router;
    }
    return null;
}

public Set<Router> getRouters() {
    return network.keySet();

}
}
