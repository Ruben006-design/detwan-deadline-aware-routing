import dataset.AbileneTopologyLoader;
import dataset.AbileneTrafficMatrixLoader;
import network.NetworkTopology;
import network.Router;
import routing.DeadlineSatisfiability;
import routing.MAPPOEpisodeResult;
import routing.MAPPORequestEpisodeDriver;
import routing.MAPPOTrainer;
import routing.NetworkState;
import routing.PathDecision;
import routing.RouteCascade;
import routing.MinHopBaseline;
import routing.RandomFeasibleBaseline;
import routing.ShortestPathBaseline;
import routing.DFFRBaseline;
import simulation.PacketSimulationResult;
import simulation.PacketSimulator;
import simulation.OmnetBatchExporter;
import traffic.TrafficFlow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Abilene integration experiment for MAPPO admission and packet simulation. */
public class Main {

    private static final int DEFAULT_FLOW_COUNT = 10;
    private static final int PACKET_QUEUE_CAPACITY = 256;
    private static final long DEFAULT_RANDOM_SEED = 42L;
    private static final double BURST_KB = 50.0;
    private static final double FLOW_DEADLINE_MS = 10.0;
    private static final double PROCESSING_DELAY_MS = 0.05;
    // Table II reports kappa_v=1 ms as a user-network delay threshold. It is
    // an upper bound, not an instruction to assign 1 ms to both endpoints.
    private static final double TABLE_II_USER_NETWORK_DELAY_THRESHOLD_MS = 1.0;

    public static void main(String[] args) throws Exception {
        ExperimentOptions options = parseOptions(args);
        List<RunSummary> summaries = new ArrayList<>();

        for (long seed : options.seeds) {
            summaries.add(runExperiment(options, seed, args));
        }

        if (summaries.size() > 1 && !options.exportOmnet) {
            printAcrossSeedSummary(summaries, options.flowCount);
        }
    }

    private static RunSummary runExperiment(ExperimentOptions options, long randomSeed,
                                            String[] commandLine)
            throws IOException {
        String topologyFile = "dataset/abilene.txt";
        String matrixFile = "dataset/directed-abilene-zhang-5min-over-6months-ALL-native/"
                + "demandMatrix-abilene-zhang-5min-20040301-0145.txt";

        NetworkTopology topology = AbileneTopologyLoader.load(topologyFile);
        List<TrafficFlow> flows = AbileneTrafficMatrixLoader.load(
                matrixFile, topology, BURST_KB, FLOW_DEADLINE_MS,
                options.sourceAccessDelayMs, options.destinationAccessDelayMs);
        int flowCount = Math.min(options.flowCount, flows.size());
        List<TrafficFlow> selectedFlows = new ArrayList<>(flows.subList(0, flowCount));
        if (options.requestHorizon > flowCount) {
            throw new IllegalArgumentException("Requested training horizon "
                    + options.requestHorizon + " exceeds the available selected flow requests ("
                    + flowCount + "). The finite dataset is not repeated within an episode.");
        }
        List<TrafficFlow> episodeRequests = new ArrayList<>(
                selectedFlows.subList(0, options.requestHorizon));

        int budgetLevels = options.budgetLevels;
        double maxBlockingPacketBytes = options.maxBlockingPacketBytes;
        NetworkState networkState = new NetworkState(
                topology, PROCESSING_DELAY_MS, maxBlockingPacketBytes);
        RouteCascade routeCascade = new RouteCascade(
                topology, PROCESSING_DELAY_MS, maxBlockingPacketBytes);
        int criticInputSize = new routing.GlobalStateEncoder(
                topology, networkState, routeCascade).getStateSize();
        MAPPOTrainer trainer = new MAPPOTrainer(
                topology, budgetLevels, criticInputSize,
                0.99, 0.95,
                options.paperTableIIHyperparameters ? 0.1 : 0.2,
                options.paperTableIIHyperparameters ? 0.0001 : 0.001,
                1.0, randomSeed);

        Path resultsRoot = options.exportDirectory == null
                ? Paths.get("results") : options.exportDirectory;
        Path runDirectory = createUniqueRunDirectory(resultsRoot, randomSeed);
        long evaluationSeed = options.evaluationSeed == null
                ? randomSeed ^ 0x3C6EF372FE94F82AL : options.evaluationSeed;

        System.out.println("\n============================================================");
        System.out.println("Abilene training: requests/episode=" + options.requestHorizon
                + ", episodes=" + options.trainingEpisodes + ", seed=" + randomSeed);
        System.out.println("Endpoint access delays (source/destination)="
                + options.sourceAccessDelayMs + "/" + options.destinationAccessDelayMs
                + " ms; Table II user-network threshold kappa_v="
                + TABLE_II_USER_NETWORK_DELAY_THRESHOLD_MS
                + " ms is a bound. Configured defaults are zero, not paper-assigned endpoint delays.");
        System.out.println("The same first " + options.requestHorizon
                + " dataset requests are replayed each episode; this is a finite-workload project choice.");
        System.out.println("Training schedule: minibatch=" + options.minibatchSize
                + ", update rounds/agent/episode=" + options.updateRounds);
        if (options.paperTableIIHyperparameters) {
            System.out.println("Table II batch-size entry 1024 is unresolved and is not interpreted or applied.");
        }

        MAPPORequestEpisodeDriver driver = new MAPPORequestEpisodeDriver(
                topology, networkState, routeCascade, trainer, budgetLevels,
                PROCESSING_DELAY_MS, FLOW_DEADLINE_MS, PACKET_QUEUE_CAPACITY, evaluationSeed);
        MAPPORequestEpisodeDriver.TrainingRunResult trainingRun = driver.train(
                selectedFlows, options.trainingEpisodes, options.requestHorizon,
                options.minibatchSize, options.updateRounds);
        episodeRequests = new ArrayList<>(trainingRun.getRequests());
        System.out.println("Training finished; per-episode checkpoint evaluations:");
        for (MAPPORequestEpisodeDriver.EpisodeSummary summary : trainingRun.getEpisodeSummaries()) {
            System.out.println("  Episode " + summary.getEpisodeNumber()
                    + ": TDR=" + String.format(Locale.ROOT, "%.4f", summary.getTimelyDeliveryRatio())
                    + " (timely=" + summary.getTimelyPackets()
                    + "/" + summary.getGeneratedPackets()
                    + ", dropped=" + summary.getDroppedPackets()
                    + ", late=" + summary.getLatePackets()
                    + ", avgDelay=" + String.format(Locale.ROOT, "%.3f", summary.getAverageEndToEndDelay()) + " ms)");
        }
        System.out.println("Starting separate final-policy evaluation.");
        System.out.println("Evaluation seed=" + evaluationSeed
                + "; action selection=stochastic sampling from final actor probabilities"
                + "; evaluation uses a separate RNG and performs no optimizer updates.");
        MAPPORequestEpisodeDriver.EvaluationRunResult evaluationRun =
                driver.evaluate(episodeRequests, options.requestHorizon, evaluationSeed);
        episodeRequests = new ArrayList<>(evaluationRun.getRequests());
        Map<Integer, MAPPOEpisodeResult> episodeResults = evaluationRun.getResults();

        int completedRoutes = 0;
        // Eq. (19a) - This finite-run flow admission rate is admitted requests / considered requests;
        // packet-level timely delivery ratio is reported separately below.
        int admittedFlows = 0;
        Map<String, Integer> rejectionReasons = new LinkedHashMap<>();
        List<PacketSimulator.FlowInput> admittedFlowInputs = new ArrayList<>();
        for (TrafficFlow flow : episodeRequests) {
            MAPPOEpisodeResult result = episodeResults.get(flow.getFlowId());
            if (result.isRouteComplete()) completedRoutes++;
            boolean admitted = result.isRouteComplete() && result.isRouteQualified();
            if (admitted) {
                admittedFlows++;
                admittedFlowInputs.add(new PacketSimulator.FlowInput(flow, result));
            } else {
                String reason = result.getRejectionReason() == null
                        ? result.getTerminationReason() : result.getRejectionReason();
                rejectionReasons.put(reason, rejectionReasons.getOrDefault(reason, 0) + 1);
            }
        }

        int criticUpdateCount = 0;
        int actorUpdateCount = 0;
        int minibatchCount = 0;
        int inactiveSamplesSkipped = 0;
        for (MAPPORequestEpisodeDriver.EpisodeSummary summary : trainingRun.getEpisodeSummaries()) {
            MAPPOTrainer.TrainingStats stats = summary.getTrainingStats();
            criticUpdateCount += stats.getCriticUpdates();
            actorUpdateCount += stats.getActorUpdates();
            minibatchCount += stats.getMinibatchesSampled();
            inactiveSamplesSkipped += stats.getInactiveActorSamplesSkipped();
        }

        System.out.println("Topology: " + topology.getRouterCount() + " routers, "
                + topology.getLinkCount() + " directed links");
        System.out.println("Workload settings: burst=" + BURST_KB + " KB, deadline="
                + FLOW_DEADLINE_MS + " ms, processing=" + PROCESSING_DELAY_MS + " ms");
        System.out.println("MAPPO parameters: budget levels=" + budgetLevels
                + ", gamma=0.99, GAE lambda=0.95, PPO clip="
                + (options.paperTableIIHyperparameters ? 0.1 : 0.2)
                + ", learning rate="
                + (options.paperTableIIHyperparameters ? 0.0001 : 0.001)
                + (options.paperTableIIHyperparameters ? " (Table II values where mapped)"
                        : " (project defaults)"));
        System.out.println("Optimizer work: minibatches=" + minibatchCount
                + ", critic updates=" + criticUpdateCount + ", actor updates="
                + actorUpdateCount + ", inactive actor samples skipped=" + inactiveSamplesSkipped);

        Path batchPath = runDirectory.resolve("abilene-seed-" + randomSeed + ".dwbatch.csv");
        int exportVersion;
        if (options.sourceAccessDelayMs != 0.0 || options.destinationAccessDelayMs != 0.0) {
            OmnetBatchExporter.writeV3(batchPath, options.topologyId, topology, episodeRequests,
                    episodeResults, PROCESSING_DELAY_MS, FLOW_DEADLINE_MS);
            exportVersion = OmnetBatchExporter.FORMAT_VERSION_WITH_ENDPOINT_ACCESS;
        } else {
            OmnetBatchExporter.writeV2(batchPath, options.topologyId, topology, episodeRequests,
                    episodeResults, PROCESSING_DELAY_MS, FLOW_DEADLINE_MS);
            exportVersion = OmnetBatchExporter.FORMAT_VERSION_WITH_TOPOLOGY;
        }
        System.out.println("\nOMNeT++ batch exported: " + batchPath.toAbsolutePath()
                + " (format V" + exportVersion + ")");
        System.out.println("The following Java packet simulation uses the same final-policy evaluation decisions.");

        // The simulator uses the existing project assumption: deterministic
        // 1000-byte packets, an initial burst at t=0, then rate-derived arrivals
        // during the 10ms flow-deadline window. This is not the paper's arrival model.
        PacketSimulator simulator = new PacketSimulator(
                topology, admittedFlowInputs, PROCESSING_DELAY_MS,
                FLOW_DEADLINE_MS, PACKET_QUEUE_CAPACITY);
        PacketSimulationResult packetResult = simulator.simulate();

        Map<Integer, double[]> perFlowDelay = new HashMap<>();
        for (PacketSimulationResult.PacketDelivery delivery : packetResult.getPacketDeliveries()) {
            double[] values = perFlowDelay.computeIfAbsent(
                    delivery.getFlowId(), key -> new double[2]);
            values[0] += delivery.getEndToEndDelay();
            values[1]++;
        }

        System.out.println("\nAggregate results");
        System.out.println("Total flows considered: " + episodeRequests.size());
        System.out.println("Route completion: " + completedRoutes + "/" + episodeRequests.size()
                + " (" + ratio(completedRoutes, episodeRequests.size()) + ")");
        System.out.println("Flows admitted: " + admittedFlows);
        System.out.println("Flows rejected: " + (episodeRequests.size() - admittedFlows));
        System.out.println("Rejection reasons: " + rejectionReasons);
        System.out.println("Total packets generated: " + packetResult.getTotalPackets());
        System.out.println("Total packets delivered: " + packetResult.getDeliveredPackets());
        System.out.println("Total packets dropped: " + packetResult.getDroppedPackets());
        System.out.println("Packets before deadline: "
                + packetResult.getPacketsDeliveredBeforeDeadline());
        System.out.println("Packets late after delivery: " + packetResult.getPacketsMissingDeadline());
        System.out.println("Overall TDR: " + packetResult.getTimelyDeliveryRatio());
        System.out.println("TDR denominator: generated packets (timely/generated)");
        System.out.println("Average E2E delay (ms): "
                + packetResult.getAverageEndToEndDelay());

        System.out.println("\nPer-flow results");
        for (TrafficFlow flow : episodeRequests) {
            MAPPOEpisodeResult episodeResult = episodeResults.get(flow.getFlowId());
            PacketSimulationResult.FlowMetrics metrics =
                    packetResult.getPerFlowMetrics().get(flow.getFlowId());
            List<String> route = new ArrayList<>();
            route.add(flow.getSource().getName());
            List<String> budgets = new ArrayList<>();
            for (PathDecision decision : episodeResult.getPathDecisions()) {
                route.add(decision.getNextHop().getName());
                budgets.add(String.format(Locale.ROOT, "%.2f", decision.getDeadlineBudget()));
            }
            String rejectionReason = episodeResult.getRejectionReason();
            double[] delay = perFlowDelay.get(flow.getFlowId());
            double averageDelay = delay == null || delay[1] == 0.0
                    ? 0.0 : delay[0] / delay[1];

            System.out.println("Flow " + flow.getFlowId() + " "
                    + flow.getSource().getName() + " -> " + flow.getDestination().getName()
                    + " | complete=" + episodeResult.isRouteComplete()
                    + " | admission=" + (episodeResult.isRouteComplete()
                            && episodeResult.isRouteQualified() ? "ADMITTED" : "REJECTED")
                    + " | reason=" + (rejectionReason == null ? "none" : rejectionReason)
                    + " | route=" + String.join(" -> ", route)
                    + " | budgets(ms)=" + budgets
                    + " | packets(gen/del/drop/timely)="
                    + metric(metrics, 0) + "/" + metric(metrics, 1) + "/"
                    + metric(metrics, 2) + "/" + metric(metrics, 3)
                    + "/late=" + (metrics == null ? 0 : metrics.getMissedDeadlinePackets())
                    + " | TDR=" + (metrics == null ? 0.0 : metrics.getTimelyDeliveryRatio())
                    + " | avgE2E(ms)=" + averageDelay);
        }

        ShortestPathBaseline.Result spResult = ShortestPathBaseline.run(
                topology, episodeRequests, PROCESSING_DELAY_MS,
                FLOW_DEADLINE_MS, PACKET_QUEUE_CAPACITY, maxBlockingPacketBytes);

        MinHopBaseline.Result mhResult = MinHopBaseline.run(
                topology, episodeRequests, PROCESSING_DELAY_MS,
                FLOW_DEADLINE_MS, PACKET_QUEUE_CAPACITY, maxBlockingPacketBytes);

        RandomFeasibleBaseline.Result rfResult = RandomFeasibleBaseline.run(
                topology, episodeRequests, PROCESSING_DELAY_MS,
                FLOW_DEADLINE_MS, PACKET_QUEUE_CAPACITY, maxBlockingPacketBytes,
                budgetLevels, evaluationSeed);

        DFFRBaseline.Result dffrResult = DFFRBaseline.run(
                topology, episodeRequests, PROCESSING_DELAY_MS,
                FLOW_DEADLINE_MS, PACKET_QUEUE_CAPACITY, maxBlockingPacketBytes,
                budgetLevels);

        List<Integer> mappoAdmitted = new ArrayList<>();
        List<Integer> mappoRejected = new ArrayList<>();
        for (TrafficFlow flow : episodeRequests) {
            MAPPOEpisodeResult ep = episodeResults.get(flow.getFlowId());
            if (ep != null && ep.isRouteComplete() && ep.isRouteQualified()) {
                mappoAdmitted.add(flow.getFlowId());
            } else {
                mappoRejected.add(flow.getFlowId());
            }
        }

        List<Integer> spAdmitted = getAdmittedIds(spResult.getFlowResults(), episodeRequests);
        List<Integer> spRejected = getRejectedIds(spResult.getFlowResults(), episodeRequests);
        List<Integer> mhAdmitted = getAdmittedIds(mhResult.getFlowResults(), episodeRequests);
        List<Integer> mhRejected = getRejectedIds(mhResult.getFlowResults(), episodeRequests);
        List<Integer> rfAdmitted = getAdmittedIds(rfResult.getFlowResults(), episodeRequests);
        List<Integer> rfRejected = getRejectedIds(rfResult.getFlowResults(), episodeRequests);
        List<Integer> dffrAdmitted = getAdmittedIds(dffrResult.getFlowResults(), episodeRequests);
        List<Integer> dffrRejected = getRejectedIds(dffrResult.getFlowResults(), episodeRequests);

        System.out.println("\n========================================================================================================================");
        System.out.println("Algorithm Comparison: DetWAN (MAPPO) vs. Educational Baselines (CEDF Scheduling)");
        System.out.println("Workload: " + episodeRequests.size() + " flows, Abilene topology, deadline="
                + FLOW_DEADLINE_MS + " ms, burst=" + BURST_KB + " KB, budget levels=" + budgetLevels);
        System.out.println("------------------------------------------------------------------------------------------------------------------------");
        System.out.printf(Locale.ROOT, "%-28s | %-16s | %-16s | %-16s | %-16s | %-16s%n",
                "Metric", "DetWAN (MAPPO)", "Shortest-Path", "Minimum-Hop", "Random-Feasible", "DFFR");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");
        System.out.printf(Locale.ROOT, "%-28s | %-16d | %-16d | %-16d | %-16d | %-16d%n",
                "Requested Flows", episodeRequests.size(), spResult.getRequestedFlows(), mhResult.getRequestedFlows(), rfResult.getRequestedFlows(), dffrResult.getRequestedFlows());
        System.out.printf(Locale.ROOT, "%-28s | %-16d | %-16d | %-16d | %-16d | %-16d%n",
                "Admitted Flows", admittedFlows, spResult.getAdmittedFlows(), mhResult.getAdmittedFlows(), rfResult.getAdmittedFlows(), dffrResult.getAdmittedFlows());
        System.out.printf(Locale.ROOT, "%-28s | %-16s | %-16s | %-16s | %-16s | %-16s%n",
                "Admitted Flow IDs", mappoAdmitted.toString(), spAdmitted.toString(), mhAdmitted.toString(), rfAdmitted.toString(), dffrAdmitted.toString());
        System.out.printf(Locale.ROOT, "%-28s | %-16s | %-16s | %-16s | %-16s | %-16s%n",
                "Rejected Flow IDs", mappoRejected.toString(), spRejected.toString(), mhRejected.toString(), rfRejected.toString(), dffrRejected.toString());
        System.out.printf(Locale.ROOT, "%-28s | %-16d | %-16d | %-16d | %-16d | %-16d%n",
                "Generated Packets", packetResult.getTotalPackets(), spResult.getGeneratedPackets(), mhResult.getGeneratedPackets(), rfResult.getGeneratedPackets(), dffrResult.getGeneratedPackets());
        System.out.printf(Locale.ROOT, "%-28s | %-16d | %-16d | %-16d | %-16d | %-16d%n",
                "Delivered Packets", packetResult.getDeliveredPackets(), spResult.getDeliveredPackets(), mhResult.getDeliveredPackets(), rfResult.getDeliveredPackets(), dffrResult.getDeliveredPackets());
        System.out.printf(Locale.ROOT, "%-28s | %-16d | %-16d | %-16d | %-16d | %-16d%n",
                "Timely Packets", packetResult.getPacketsDeliveredBeforeDeadline(), spResult.getTimelyPackets(), mhResult.getTimelyPackets(), rfResult.getTimelyPackets(), dffrResult.getTimelyPackets());
        System.out.printf(Locale.ROOT, "%-28s | %-16d | %-16d | %-16d | %-16d | %-16d%n",
                "Late Packets", packetResult.getPacketsMissingDeadline(), spResult.getLatePackets(), mhResult.getLatePackets(), rfResult.getLatePackets(), dffrResult.getLatePackets());
        System.out.printf(Locale.ROOT, "%-28s | %-16d | %-16d | %-16d | %-16d | %-16d%n",
                "Dropped Packets", packetResult.getDroppedPackets(), spResult.getDroppedPackets(), mhResult.getDroppedPackets(), rfResult.getDroppedPackets(), dffrResult.getDroppedPackets());
        System.out.printf(Locale.ROOT, "%-28s | %-16.4f | %-16.4f | %-16.4f | %-16.4f | %-16.4f%n",
                "TDR (timely/generated)", packetResult.getTimelyDeliveryRatio(), spResult.getTimelyDeliveryRatio(), mhResult.getTimelyDeliveryRatio(), rfResult.getTimelyDeliveryRatio(), dffrResult.getTimelyDeliveryRatio());
        System.out.printf(Locale.ROOT, "%-28s | %-16.3f | %-16.3f | %-16.3f | %-16.3f | %-16.3f%n",
                "Average E2E Delay (ms)", packetResult.getAverageEndToEndDelay(), spResult.getAverageEndToEndDelay(), mhResult.getAverageEndToEndDelay(), rfResult.getAverageEndToEndDelay(), dffrResult.getAverageEndToEndDelay());
        System.out.println("========================================================================================================================\n");

        writeExperimentRecord(runDirectory, commandLine, options, randomSeed, evaluationSeed,
                topologyFile, matrixFile, topology, trainingRun, episodeRequests, episodeResults,
                packetResult, batchPath, exportVersion, completedRoutes, admittedFlows,
                rejectionReasons);

        StringBuilder compCsv = new StringBuilder(
                "method,seed,requested_flows,admitted_flows,admitted_flow_ids,rejected_flow_ids,generated_packets,delivered_packets,timely_packets,late_packets,dropped_packets,tdr,average_delay_ms\n");
        appendSummaryRow(compCsv, "DetWAN_MAPPO", randomSeed, episodeRequests.size(), admittedFlows,
                mappoAdmitted, mappoRejected, packetResult.getTotalPackets(), packetResult.getDeliveredPackets(),
                packetResult.getPacketsDeliveredBeforeDeadline(), packetResult.getPacketsMissingDeadline(),
                packetResult.getDroppedPackets(), packetResult.getTimelyDeliveryRatio(), packetResult.getAverageEndToEndDelay());
        appendSummaryRow(compCsv, "ShortestPath_CEDF", randomSeed, spResult.getRequestedFlows(), spResult.getAdmittedFlows(),
                spAdmitted, spRejected, spResult.getGeneratedPackets(), spResult.getDeliveredPackets(),
                spResult.getTimelyPackets(), spResult.getLatePackets(), spResult.getDroppedPackets(),
                spResult.getTimelyDeliveryRatio(), spResult.getAverageEndToEndDelay());
        appendSummaryRow(compCsv, "MinHop_CEDF", randomSeed, mhResult.getRequestedFlows(), mhResult.getAdmittedFlows(),
                mhAdmitted, mhRejected, mhResult.getGeneratedPackets(), mhResult.getDeliveredPackets(),
                mhResult.getTimelyPackets(), mhResult.getLatePackets(), mhResult.getDroppedPackets(),
                mhResult.getTimelyDeliveryRatio(), mhResult.getAverageEndToEndDelay());
        appendSummaryRow(compCsv, "RandomFeasible_CEDF", randomSeed, rfResult.getRequestedFlows(), rfResult.getAdmittedFlows(),
                rfAdmitted, rfRejected, rfResult.getGeneratedPackets(), rfResult.getDeliveredPackets(),
                rfResult.getTimelyPackets(), rfResult.getLatePackets(), rfResult.getDroppedPackets(),
                rfResult.getTimelyDeliveryRatio(), rfResult.getAverageEndToEndDelay());
        appendSummaryRow(compCsv, "DFFR_CEDF", randomSeed, dffrResult.getRequestedFlows(), dffrResult.getAdmittedFlows(),
                dffrAdmitted, dffrRejected, dffrResult.getGeneratedPackets(), dffrResult.getDeliveredPackets(),
                dffrResult.getTimelyPackets(), dffrResult.getLatePackets(), dffrResult.getDroppedPackets(),
                dffrResult.getTimelyDeliveryRatio(), dffrResult.getAverageEndToEndDelay());
        Files.write(runDirectory.resolve("comparison.csv"), compCsv.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW);

        // Export detailed per-flow metrics for all five routing methods.
        StringBuilder flowCsv = new StringBuilder(
                "method,seed,flow_id,source,destination,route_complete,admitted,rejection_reason,route,budgets,generated_packets,delivered_packets,timely_packets,late_packets,dropped_packets,tdr\n");
        for (TrafficFlow flow : episodeRequests) {
            appendFlowRow(flowCsv, "DetWAN_MAPPO", randomSeed, flow, episodeResults.get(flow.getFlowId()), packetResult);
            appendFlowRow(flowCsv, "ShortestPath_CEDF", randomSeed, flow, spResult.getFlowResults().get(flow.getFlowId()), spResult.getSimulationResult());
            appendFlowRow(flowCsv, "MinHop_CEDF", randomSeed, flow, mhResult.getFlowResults().get(flow.getFlowId()), mhResult.getSimulationResult());
            appendFlowRow(flowCsv, "RandomFeasible_CEDF", randomSeed, flow, rfResult.getFlowResults().get(flow.getFlowId()), rfResult.getSimulationResult());
            appendFlowRow(flowCsv, "DFFR_CEDF", randomSeed, flow, dffrResult.getFlowResults().get(flow.getFlowId()), dffrResult.getSimulationResult());
        }
        Files.write(runDirectory.resolve("comparison_flows.csv"), flowCsv.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW);
        System.out.println("Run record directory: " + runDirectory.toAbsolutePath());

        return new RunSummary(randomSeed, episodeRequests.size(), completedRoutes,
                admittedFlows, packetResult, runDirectory);
    }

    private static Path createUniqueRunDirectory(Path root, long seed) throws IOException {
        Files.createDirectories(root);
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
                .withZone(ZoneOffset.UTC).format(Instant.now());
        String base = "detwan-run-" + timestamp + "-seed-" + seed;
        Path candidate = root.resolve(base);
        int suffix = 1;
        while (Files.exists(candidate)) candidate = root.resolve(base + "-" + suffix++);
        return Files.createDirectory(candidate);
    }

    private static void writeExperimentRecord(
            Path runDirectory, String[] commandLine, ExperimentOptions options,
            long trainingSeed, long evaluationSeed, String topologyFile, String matrixFile,
            NetworkTopology topology, MAPPORequestEpisodeDriver.TrainingRunResult trainingRun,
            List<TrafficFlow> flows, Map<Integer, MAPPOEpisodeResult> outcomes,
            PacketSimulationResult packets, Path batchPath, int exportVersion,
            int completedRoutes, int admittedFlows, Map<String, Integer> rejectionReasons)
            throws IOException {
        String command = String.join(" ", commandLine);
        String properties = "command=" + property(command) + "\n"
                + "training_seed=" + trainingSeed + "\n"
                + "evaluation_seed=" + evaluationSeed + "\n"
                + "evaluation_action_selection=sampled_from_final_policy\n"
                + "evaluation_updates_model=false\n"
                + "topology_id=" + property(options.topologyId) + "\n"
                + "topology_file=" + property(topologyFile) + "\n"
                + "topology_routers=" + topology.getRouterCount() + "\n"
                + "topology_directed_links=" + topology.getLinkCount() + "\n"
                + "traffic_matrix=" + property(matrixFile) + "\n"
                + "flows_considered=" + flows.size() + "\n"
                + "training_episodes=" + options.trainingEpisodes + "\n"
                + "request_horizon=" + options.requestHorizon + "\n"
                + "minibatch_size=" + options.minibatchSize + "\n"
                + "update_rounds_per_agent_per_episode=" + options.updateRounds + "\n"
                + "max_blocking_packet_bytes=" + options.maxBlockingPacketBytes + "\n"
                + "max_blocking_packet_bytes_source=project_parameter_not_specified_by_paper\n"
                + "endpoint_source_delay_ms=" + options.sourceAccessDelayMs + "\n"
                + "endpoint_destination_delay_ms=" + options.destinationAccessDelayMs + "\n"
                + "burst_kb_decimal=" + BURST_KB + "\n"
                + "packet_size_bytes=" + OmnetBatchExporter.PACKET_SIZE_BYTES + "\n"
                + "deadline_ms=" + FLOW_DEADLINE_MS + "\n"
                + "processing_delay_ms=" + PROCESSING_DELAY_MS + "\n"
                + "queue_capacity_packets=" + PACKET_QUEUE_CAPACITY + "\n"
                + "admitted_flows=" + admittedFlows + "\n"
                + "rejected_flows=" + (flows.size() - admittedFlows) + "\n"
                + "route_complete_flows=" + completedRoutes + "\n"
                + "rejection_reasons=" + property(rejectionReasons.toString()) + "\n"
                + "batch_format_version=" + exportVersion + "\n"
                + "batch_file=" + property(batchPath.toAbsolutePath().toString()) + "\n"
                + "packet_tdr_definition=timely_delivered/generated_packets\n"
                + "average_e2e_delay_definition=all_delivered_packets_including_late\n"
                + "java_runtime=success\n";
        Files.write(runDirectory.resolve("run.properties"),
                properties.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);

        StringBuilder trainCsv = new StringBuilder("episode,requests,transitions,minibatches,actor_updates,critic_updates,inactive_actor_samples_skipped,"
                + "generated_packets,delivered_packets,timely_packets,late_packets,dropped_packets,tdr,average_delay_ms\n");
        for (MAPPORequestEpisodeDriver.EpisodeSummary summary : trainingRun.getEpisodeSummaries()) {
            MAPPOTrainer.TrainingStats stats = summary.getTrainingStats();
            trainCsv.append(summary.getEpisodeNumber()).append(',')
                    .append(summary.getRequestsProcessed()).append(',')
                    .append(summary.getTransitionsCollected()).append(',')
                    .append(stats.getMinibatchesSampled()).append(',')
                    .append(stats.getActorUpdates()).append(',')
                    .append(stats.getCriticUpdates()).append(',')
                    .append(stats.getInactiveActorSamplesSkipped()).append(',')
                    .append(summary.getGeneratedPackets()).append(',')
                    .append(summary.getDeliveredPackets()).append(',')
                    .append(summary.getTimelyPackets()).append(',')
                    .append(summary.getLatePackets()).append(',')
                    .append(summary.getDroppedPackets()).append(',')
                    .append(String.format(Locale.ROOT, "%.6f", summary.getTimelyDeliveryRatio())).append(',')
                    .append(String.format(Locale.ROOT, "%.6f", summary.getAverageEndToEndDelay())).append('\n');
        }
        Files.write(runDirectory.resolve("training.csv"), trainCsv.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW);

        StringBuilder flowCsv = new StringBuilder("flow_id,source,destination,route_complete,admission,rejection_reason,route,local_delta_budgets_ms,generated_packets,delivered_packets,dropped_packets,timely_packets,late_delivered_packets,tdr_timely_over_generated,average_delay_ms\n");
        for (TrafficFlow flow : flows) {
            MAPPOEpisodeResult outcome = outcomes.get(flow.getFlowId());
            PacketSimulationResult.FlowMetrics metrics = packets.getPerFlowMetrics().get(flow.getFlowId());
            List<String> nodes = new ArrayList<>(); nodes.add(flow.getSource().getName());
            List<String> budgets = new ArrayList<>();
            for (PathDecision decision : outcome.getPathDecisions()) {
                nodes.add(decision.getNextHop().getName());
                budgets.add(Double.toString(decision.getDeadlineBudget()));
            }
            double delayTotal = 0.0; int delayCount = 0;
            for (PacketSimulationResult.PacketDelivery delivery : packets.getPacketDeliveries()) {
                if (delivery.getFlowId() == flow.getFlowId()) { delayTotal += delivery.getEndToEndDelay(); delayCount++; }
            }
            flowCsv.append(flow.getFlowId()).append(',').append(csv(flow.getSource().getName())).append(',')
                    .append(csv(flow.getDestination().getName())).append(',').append(outcome.isRouteComplete()).append(',')
                    .append(outcome.isRouteComplete() && outcome.isRouteQualified() ? "ADMITTED" : "REJECTED").append(',')
                    .append(csv(outcome.getRejectionReason() == null ? "" : outcome.getRejectionReason())).append(',')
                    .append(csv(String.join(";", nodes))).append(',').append(csv(String.join(";", budgets))).append(',')
                    .append(metrics == null ? 0 : metrics.getGeneratedPackets()).append(',')
                    .append(metrics == null ? 0 : metrics.getDeliveredPackets()).append(',')
                    .append(metrics == null ? 0 : metrics.getDroppedPackets()).append(',')
                    .append(metrics == null ? 0 : metrics.getTimelyPackets()).append(',')
                    .append(metrics == null ? 0 : metrics.getMissedDeadlinePackets()).append(',')
                    .append(metrics == null ? 0.0 : metrics.getTimelyDeliveryRatio()).append(',')
                    .append(delayCount == 0 ? 0.0 : delayTotal / delayCount).append('\n');
        }
        Files.write(runDirectory.resolve("flows.csv"), flowCsv.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW);
        String summaryCsv = "metric,value,denominator_or_definition\n"
                + "flows_considered," + flows.size() + ",evaluation requests\n"
                + "route_complete," + completedRoutes + ",flows considered\n"
                + "admitted_flows," + admittedFlows + ",flows considered; route complete and qualified\n"
                + "rejected_flows," + (flows.size() - admittedFlows) + ",flows considered\n"
                + "generated_packets," + packets.getTotalPackets() + ",admitted flows only\n"
                + "delivered_packets," + packets.getDeliveredPackets() + ",generated packets\n"
                + "dropped_packets," + packets.getDroppedPackets() + ",generated packets\n"
                + "timely_packets," + packets.getPacketsDeliveredBeforeDeadline() + ",generated packets\n"
                + "late_delivered_packets," + packets.getPacketsMissingDeadline() + ",delivered packets\n"
                + "packet_tdr," + packets.getTimelyDeliveryRatio() + ",timely packets / generated packets\n"
                + "average_e2e_delay_ms," + packets.getAverageEndToEndDelay() + ",delivered packets including late\n";
        Files.write(runDirectory.resolve("summary.csv"), summaryCsv.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW);
        String log = "DetWAN train-evaluate-export-simulate report\n" + command + "\n"
                + "evaluation_seed=" + evaluationSeed + " (sampled final-policy actions; no training updates)\n"
                + "admitted_flows=" + admittedFlows + "/" + flows.size() + "\n"
                + "rejections=" + rejectionReasons + "\n"
                + "packets generated/delivered/dropped/timely/late=" + packets.getTotalPackets() + "/"
                + packets.getDeliveredPackets() + "/" + packets.getDroppedPackets() + "/"
                + packets.getPacketsDeliveredBeforeDeadline() + "/" + packets.getPacketsMissingDeadline() + "\n"
                + "packet_tdr(timely/generated)=" + packets.getTimelyDeliveryRatio() + "\n"
                + "average_e2e_delay_ms(delivered_including_late)=" + packets.getAverageEndToEndDelay() + "\n"
                + "batch_file=" + batchPath.toAbsolutePath() + "\n";
        Files.write(runDirectory.resolve("run.log"), log.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW);
    }

    private static String property(String value) { return value.replace("\\", "\\\\").replace("\n", " "); }
    private static String csv(String value) { return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\""; }

    private static int metric(PacketSimulationResult.FlowMetrics metrics, int kind) {
        if (metrics == null) return 0;
        switch (kind) {
            case 0: return metrics.getGeneratedPackets();
            case 1: return metrics.getDeliveredPackets();
            case 2: return metrics.getDroppedPackets();
            default: return metrics.getTimelyPackets();
        }
    }

    private static String ratio(int numerator, int denominator) {
        return denominator == 0 ? "n/a" : String.format(Locale.ROOT, "%.3f", (double) numerator / denominator);
    }

    private static List<Integer> getAdmittedIds(Map<Integer, MAPPOEpisodeResult> results, List<TrafficFlow> flows) {
        List<Integer> list = new ArrayList<>();
        if (results == null || flows == null) return list;
        for (TrafficFlow flow : flows) {
            MAPPOEpisodeResult ep = results.get(flow.getFlowId());
            if (ep != null && ep.isRouteComplete() && ep.isRouteQualified()) {
                list.add(flow.getFlowId());
            }
        }
        return list;
    }

    private static List<Integer> getRejectedIds(Map<Integer, MAPPOEpisodeResult> results, List<TrafficFlow> flows) {
        List<Integer> list = new ArrayList<>();
        if (results == null || flows == null) return list;
        for (TrafficFlow flow : flows) {
            MAPPOEpisodeResult ep = results.get(flow.getFlowId());
            if (ep == null || !ep.isRouteComplete() || !ep.isRouteQualified()) {
                list.add(flow.getFlowId());
            }
        }
        return list;
    }

    private static void appendSummaryRow(StringBuilder sb, String method, long seed, int requested, int admitted,
                                         List<Integer> admittedIds, List<Integer> rejectedIds,
                                         int gen, int del, int timely, int late, int dropped,
                                         double tdr, double avgDelay) {
        sb.append(method).append(',')
                .append(seed).append(',')
                .append(requested).append(',')
                .append(admitted).append(',')
                .append(csv(admittedIds.toString())).append(',')
                .append(csv(rejectedIds.toString())).append(',')
                .append(gen).append(',')
                .append(del).append(',')
                .append(timely).append(',')
                .append(late).append(',')
                .append(dropped).append(',')
                .append(String.format(Locale.ROOT, "%.6f", tdr)).append(',')
                .append(String.format(Locale.ROOT, "%.6f", avgDelay)).append('\n');
    }

    private static void appendFlowRow(StringBuilder sb, String method, long seed, TrafficFlow flow,
                                      MAPPOEpisodeResult epResult, PacketSimulationResult simResult) {
        boolean complete = epResult != null && epResult.isRouteComplete();
        boolean admitted = epResult != null && epResult.isRouteComplete() && epResult.isRouteQualified();
        String reason = epResult == null || epResult.getRejectionReason() == null ? "" : epResult.getRejectionReason();

        List<String> nodes = new ArrayList<>();
        List<String> budgets = new ArrayList<>();
        if (epResult != null && !epResult.getPathDecisions().isEmpty()) {
            for (PathDecision hop : epResult.getPathDecisions()) {
                nodes.add(hop.getRouter().getName());
                budgets.add(String.format(Locale.ROOT, "%.2f", hop.getDeadlineBudget()));
            }
            nodes.add(epResult.getPathDecisions().get(epResult.getPathDecisions().size() - 1).getNextHop().getName());
        }

        PacketSimulationResult.FlowMetrics m = simResult != null && simResult.getPerFlowMetrics() != null
                ? simResult.getPerFlowMetrics().get(flow.getFlowId()) : null;

        sb.append(method).append(',')
                .append(seed).append(',')
                .append(flow.getFlowId()).append(',')
                .append(csv(flow.getSource().getName())).append(',')
                .append(csv(flow.getDestination().getName())).append(',')
                .append(complete).append(',')
                .append(admitted ? "ADMITTED" : "REJECTED").append(',')
                .append(csv(reason)).append(',')
                .append(csv(String.join(" -> ", nodes))).append(',')
                .append(csv(budgets.toString())).append(',')
                .append(m == null ? 0 : m.getGeneratedPackets()).append(',')
                .append(m == null ? 0 : m.getDeliveredPackets()).append(',')
                .append(m == null ? 0 : m.getTimelyPackets()).append(',')
                .append(m == null ? 0 : m.getMissedDeadlinePackets()).append(',')
                .append(m == null ? 0 : m.getDroppedPackets()).append(',')
                .append(String.format(Locale.ROOT, "%.6f", m == null ? 0.0 : m.getTimelyDeliveryRatio())).append('\n');
    }

    private static ExperimentOptions parseOptions(String[] args) {
        int flowCount = DEFAULT_FLOW_COUNT;
        long[] seeds = new long[] { DEFAULT_RANDOM_SEED };
        Path exportDirectory = null;
        Long evaluationSeedOverride = null;
        String topologyId = "abilene-sndlib-12n15l-v1";
        Integer episodesOverride = null;
        Integer horizonOverride = null;
        Integer minibatchOverride = null;
        Integer updateRoundsOverride = null;
        int budgetLevels = 16;
        double sourceAccessDelayMs = 0.0;
        double destinationAccessDelayMs = 0.0;
        double maxBlockingPacketBytes =
                DeadlineSatisfiability.DEFAULT_MAX_BLOCKING_PACKET_BYTES;
        boolean paperTableIIHyperparameters = false;

        // Preserve the old `Main <seed>` invocation for baseline reproduction.
        if (args.length == 1 && !args[0].startsWith("--")) {
            seeds = new long[] { Long.parseLong(args[0]) };
        }

        for (int i = 0; i < args.length; i++) {
            if (args.length == 1 && !args[0].startsWith("--")) break;
            if (args[i].equals("--flows") && i + 1 < args.length) {
                flowCount = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--episodes") && i + 1 < args.length) {
                episodesOverride = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--horizon") && i + 1 < args.length) {
                horizonOverride = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--minibatch-size") && i + 1 < args.length) {
                minibatchOverride = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--update-rounds") && i + 1 < args.length) {
                updateRoundsOverride = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--budget-levels") && i + 1 < args.length) {
                budgetLevels = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--source-access-delay-ms") && i + 1 < args.length) {
                sourceAccessDelayMs = Double.parseDouble(args[++i]);
            } else if (args[i].equals("--destination-access-delay-ms") && i + 1 < args.length) {
                destinationAccessDelayMs = Double.parseDouble(args[++i]);
            } else if (args[i].equals("--max-blocking-packet-bytes")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(
                            "Missing value for --max-blocking-packet-bytes; expected a finite, non-negative whole number of bytes.");
                }
                maxBlockingPacketBytes = parseMaxBlockingPacketBytes(args[++i]);
            } else if (args[i].equals("--seeds") && i + 1 < args.length) {
                String[] parts = args[++i].split(",");
                seeds = new long[parts.length];
                for (int seedIndex = 0; seedIndex < parts.length; seedIndex++) {
                    seeds[seedIndex] = Long.parseLong(parts[seedIndex].trim());
                }
            } else if (args[i].equals("--evaluation-seed") && i + 1 < args.length) {
                evaluationSeedOverride = Long.parseLong(args[++i]);
            } else if ((args[i].equals("--export-omnet") || args[i].equals("--results-root"))
                    && i + 1 < args.length) {
                exportDirectory = Paths.get(args[++i]);
            } else if (args[i].equals("--topology-id") && i + 1 < args.length) {
                topologyId = args[++i];
            } else if (args[i].equals("--paper-table-ii")) {
                paperTableIIHyperparameters = true;
            } else {
                throw new IllegalArgumentException(
                        "Usage: Main [--flows count] [--seeds seed[,seed...]] "
                        + "[--evaluation-seed seed] "
                        + "[--episodes count] [--horizon requests] [--minibatch-size S] "
                        + "[--update-rounds count] "
                        + "[--budget-levels count] "
                        + "[--source-access-delay-ms value] [--destination-access-delay-ms value] "
                        + "[--max-blocking-packet-bytes value] "
                        + "[--results-root path] [--export-omnet path (legacy alias)] [--topology-id id] "
                        + "[--paper-table-ii] applies Table II budget/clip/learning-rate values; "
                        + "legacy form Main <seed> is also supported.");
            }
        }
        if (flowCount <= 0 || seeds.length == 0 || budgetLevels <= 0) {
            throw new IllegalArgumentException("Flow count, seed list, and budget levels must be positive/non-empty.");
        }
        if (!Double.isFinite(sourceAccessDelayMs) || sourceAccessDelayMs < 0.0
                || sourceAccessDelayMs > TABLE_II_USER_NETWORK_DELAY_THRESHOLD_MS
                || !Double.isFinite(destinationAccessDelayMs) || destinationAccessDelayMs < 0.0
                || destinationAccessDelayMs > TABLE_II_USER_NETWORK_DELAY_THRESHOLD_MS) {
            throw new IllegalArgumentException("Configured source/destination access delays must be finite,"
                    + " non-negative, and no greater than the Table II user-network threshold (1 ms).");
        }
        if (!Double.isFinite(maxBlockingPacketBytes) || maxBlockingPacketBytes < 0.0
                || maxBlockingPacketBytes != Math.rint(maxBlockingPacketBytes)) {
            throw new IllegalArgumentException(
                    "--max-blocking-packet-bytes must be a finite, non-negative whole number of bytes; got "
                            + maxBlockingPacketBytes + ".");
        }
        int episodes = episodesOverride != null ? episodesOverride
                : paperTableIIHyperparameters ? 5000 : 1;
        int horizon = horizonOverride != null ? horizonOverride
                : paperTableIIHyperparameters ? 1000 : flowCount;
        int minibatchSize = minibatchOverride != null ? minibatchOverride : 4;
        int updateRounds;
        if (updateRoundsOverride != null) {
            updateRounds = updateRoundsOverride;
        } else if (paperTableIIHyperparameters) {
            if (horizon % minibatchSize != 0) {
                throw new IllegalArgumentException("Paper schedule requires T divisible by S to use T/S rounds");
            }
            updateRounds = horizon / minibatchSize;
        } else {
            updateRounds = 1;
        }
        if (episodes <= 0 || horizon <= 0 || minibatchSize <= 0 || updateRounds <= 0) {
            throw new IllegalArgumentException("Episode, horizon, minibatch, and update-round values must be positive.");
        }
        if (minibatchSize > horizon) {
            throw new IllegalArgumentException("Minibatch size cannot exceed the request horizon.");
        }
        return new ExperimentOptions(flowCount, seeds, exportDirectory, topologyId,
                paperTableIIHyperparameters, episodes, horizon, minibatchSize, updateRounds,
                sourceAccessDelayMs, destinationAccessDelayMs, maxBlockingPacketBytes,
                evaluationSeedOverride, budgetLevels);
    }

    private static double parseMaxBlockingPacketBytes(String rawValue) {
        final double value;
        try {
            value = Double.parseDouble(rawValue);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid --max-blocking-packet-bytes value '" + rawValue
                            + "'; expected a finite, non-negative whole number of bytes.", e);
        }
        if (!Double.isFinite(value) || value < 0.0 || value != Math.rint(value)) {
            throw new IllegalArgumentException(
                    "Invalid --max-blocking-packet-bytes value '" + rawValue
                            + "'; expected a finite, non-negative whole number of bytes.");
        }
        return value;
    }

    private static void printAcrossSeedSummary(List<RunSummary> runs, int requestedFlowCount) {
        double meanCompletion = 0.0;
        double meanAdmission = 0.0;
        double meanTdr = 0.0;
        double meanDelay = 0.0;
        System.out.println("\nAcross-seed summary (" + runs.size() + " independent runs)");
        for (RunSummary run : runs) {
            double completion = (double) run.completedRoutes / run.flowCount;
            double admission = (double) run.admittedFlows / run.flowCount;
            meanCompletion += completion;
            meanAdmission += admission;
            meanTdr += run.packetResult.getTimelyDeliveryRatio();
            meanDelay += run.packetResult.getAverageEndToEndDelay();
            System.out.println("seed=" + run.seed + ": completed=" + run.completedRoutes + "/"
                    + run.flowCount + ", admitted=" + run.admittedFlows + "/" + run.flowCount
                    + ", generated=" + run.packetResult.getTotalPackets()
                    + ", delivered=" + run.packetResult.getDeliveredPackets()
                    + ", dropped=" + run.packetResult.getDroppedPackets()
                    + ", TDR=" + run.packetResult.getTimelyDeliveryRatio()
                    + ", avgE2E(ms)=" + run.packetResult.getAverageEndToEndDelay());
        }
        int n = runs.size();
        System.out.println("Means for requested flow count " + requestedFlowCount
                + ": route completion=" + meanCompletion / n
                + ", admission=" + meanAdmission / n
                + ", packet TDR=" + meanTdr / n
                + ", avgE2E(ms)=" + meanDelay / n);
    }

    private static final class ExperimentOptions {
        final int flowCount;
        final long[] seeds;
        final Path exportDirectory;
        final String topologyId;
        final boolean exportOmnet;
        final boolean paperTableIIHyperparameters;
        final int trainingEpisodes;
        final int requestHorizon;
        final int minibatchSize;
        final int updateRounds;
        final double sourceAccessDelayMs;
        final double destinationAccessDelayMs;
        final double maxBlockingPacketBytes;
        final Long evaluationSeed;
        final int budgetLevels;
        ExperimentOptions(int flowCount, long[] seeds, Path exportDirectory,
                          String topologyId, boolean paperTableIIHyperparameters,
                          int trainingEpisodes, int requestHorizon,
                          int minibatchSize, int updateRounds,
                          double sourceAccessDelayMs, double destinationAccessDelayMs,
                          double maxBlockingPacketBytes,
                          Long evaluationSeed, int budgetLevels) {
            this.flowCount = flowCount;
            this.seeds = Arrays.copyOf(seeds, seeds.length);
            this.exportDirectory = exportDirectory;
            this.topologyId = topologyId;
            this.exportOmnet = exportDirectory != null;
            this.paperTableIIHyperparameters = paperTableIIHyperparameters;
            this.trainingEpisodes = trainingEpisodes;
            this.requestHorizon = requestHorizon;
            this.minibatchSize = minibatchSize;
            this.updateRounds = updateRounds;
            this.sourceAccessDelayMs = sourceAccessDelayMs;
            this.destinationAccessDelayMs = destinationAccessDelayMs;
            this.maxBlockingPacketBytes = maxBlockingPacketBytes;
            this.evaluationSeed = evaluationSeed;
            this.budgetLevels = budgetLevels;
        }
    }

    private static final class RunSummary {
        final long seed;
        final int flowCount;
        final int completedRoutes;
        final int admittedFlows;
        final PacketSimulationResult packetResult;
        final Path runDirectory;
        RunSummary(long seed, int flowCount, int completedRoutes,
                   int admittedFlows, PacketSimulationResult packetResult, Path runDirectory) {
            this.seed = seed;
            this.flowCount = flowCount;
            this.completedRoutes = completedRoutes;
            this.admittedFlows = admittedFlows;
            this.packetResult = packetResult;
            this.runDirectory = runDirectory;
        }
    }
}
