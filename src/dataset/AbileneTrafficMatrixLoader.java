package dataset;

import java.io.*;
import java.util.*;
import network.*;
import traffic.TrafficFlow;

public class AbileneTrafficMatrixLoader {

    public static List<TrafficFlow> load(
            String filePath,
            NetworkTopology topology,
            double burstKB,
            double deadlineMs) throws IOException {

        return load(filePath, topology, burstKB, deadlineMs, 0.0, 0.0);
    }

    /** Loads the existing dataset with explicit project-configured endpoint access delays. */
    public static List<TrafficFlow> load(
            String filePath,
            NetworkTopology topology,
            double burstKB,
            double deadlineMs,
            double sourceAccessDelayMs,
            double destinationAccessDelayMs) throws IOException {

        List<TrafficFlow> flows = new ArrayList<>();

        BufferedReader reader =
                new BufferedReader(new FileReader(filePath));

        String line;
        boolean inDemands = false;
        int flowId = 1;
        int demandLines = 0;

        while ((line = reader.readLine()) != null) {

            line = line.trim();

            if (line.equals("DEMANDS (")) {
                inDemands = true;
                continue;
            }

            if (inDemands && line.equals(")")) {
                break;
            }

            if (!inDemands ||
                    line.isEmpty() ||
                    line.startsWith("#")) {
                continue;
            }

            demandLines++;

            int open = line.indexOf('(');
            int close = line.indexOf(')');

            if (open == -1 || close == -1) {
                continue;
            }

            String endpoints =
                    line.substring(open + 1, close).trim();

            String[] nodes =
                    endpoints.split("\\s+");

            if (nodes.length != 2) {
                continue;
            }

            String remaining =
                    line.substring(close + 1).trim();

            String[] parts =
                    remaining.split("\\s+");

            if (parts.length < 2) {
                continue;
            }

            double rateMbps =
                    Double.parseDouble(parts[1]);

            Router source =
                    topology.getRouter(nodes[0]);

            Router destination =
                    topology.getRouter(nodes[1]);

            if (source == null || destination == null) {
                continue;
            }

            TrafficFlow flow =
                    new TrafficFlow(
                            flowId++,
                            source,
                            destination,
                            rateMbps,
                            burstKB,
                            deadlineMs,
                            sourceAccessDelayMs,
                            destinationAccessDelayMs
                    );

            flows.add(flow);
        }

        reader.close();

        System.out.println(
                "Demand lines found: " + demandLines);

        return flows;
    }
}
