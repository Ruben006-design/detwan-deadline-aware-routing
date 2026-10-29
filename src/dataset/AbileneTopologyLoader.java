package dataset;

import java.io.*;
import java.util.*;
import network.*;

public class AbileneTopologyLoader {

    public static NetworkTopology load(String filePath) throws IOException {

        NetworkTopology topology = new NetworkTopology();
        Map<String, Router> routers = new HashMap<>();

        BufferedReader reader = new BufferedReader(new FileReader(filePath));

        String line;
        boolean inNodes = false;
        boolean inLinks = false;
        int routerId = 0;

        while ((line = reader.readLine()) != null) {

            line = line.trim();

            if (line.equals("NODES (")) {
                inNodes = true;
                inLinks = false;
                continue;
            }

            if (line.equals("LINKS (")) {
                inNodes = false;
                inLinks = true;
                continue;
            }

            if (line.equals(")")) {
                inNodes = false;
                inLinks = false;
                continue;
            }

            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (inNodes) {

                int open = line.indexOf('(');
                int close = line.indexOf(')');

                if (open != -1 && close != -1) {

                    String nodeName =
                            line.substring(0, open).trim();

                    Router router =
                            new Router(routerId++, nodeName);

                    routers.put(nodeName, router);
                    topology.addRouter(router);
                }
            }

            if (inLinks) {

                int open = line.indexOf('(');
                int close = line.indexOf(')');

                if (open != -1 && close != -1) {

                    String endpoints =
                            line.substring(open + 1, close).trim();

                    String[] nodes =
                            endpoints.split("\\s+");

                    if (nodes.length == 2) {

                        String sourceName = nodes[0];
                        String destinationName = nodes[1];

                        String remaining =
                                line.substring(close + 1).trim();

                        String[] parts =
                                remaining.split("\\s+");

                        if (parts.length > 0) {

                            double bandwidth =
                                    Double.parseDouble(parts[0]);

                            Router source =
                                    routers.get(sourceName);

                            Router destination =
                                    routers.get(destinationName);

                            if (source != null && destination != null) {

                                Link link = new Link(
                                        source,
                                        destination,
                                        2.0,
                                        bandwidth
                                );

                                topology.addLink(link);
                            }
                        }
                    }
                }
            }
        }

        reader.close();

        return topology;
    }
}