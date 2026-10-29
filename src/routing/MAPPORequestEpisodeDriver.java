package routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import network.NetworkTopology;
import simulation.PacketSimulationResult;
import simulation.PacketSimulator;
import traffic.TrafficFlow;

/**
 * Collects request-driven MAPPO rollouts and updates once at each episode
 * boundary. The supplied finite request list is replayed from its beginning
 * for each episode; this is an explicit project workload choice, not a claim
 * that it reproduces the paper's request-generation process.
 */
public final class MAPPORequestEpisodeDriver {
    private final NetworkTopology topology;
    private final NetworkState networkState;
    private final RouteCascade routeCascade;
    private final MAPPOTrainer trainer;
    private final int budgetLevels;
    private final double processingDelayMs;
    private final double simulationWindowMs;
    private final int queueCapacity;
    private final long evaluationSeed;
    private final boolean evaluateCheckpoints;

    public MAPPORequestEpisodeDriver(NetworkTopology topology,
                                     NetworkState networkState,
                                     RouteCascade routeCascade,
                                     MAPPOTrainer trainer,
                                     int budgetLevels) {
        this(topology, networkState, routeCascade, trainer, budgetLevels,
                networkState != null ? networkState.getProcessingDelay() : 0.05,
                10.0, 256, 42L, true);
    }

    public MAPPORequestEpisodeDriver(NetworkTopology topology,
                                     NetworkState networkState,
                                     RouteCascade routeCascade,
                                     MAPPOTrainer trainer,
                                     int budgetLevels,
                                     double processingDelayMs,
                                     double simulationWindowMs,
                                     int queueCapacity,
                                     long evaluationSeed) {
        this(topology, networkState, routeCascade, trainer, budgetLevels,
                processingDelayMs, simulationWindowMs, queueCapacity, evaluationSeed, true);
    }

    public MAPPORequestEpisodeDriver(NetworkTopology topology,
                                     NetworkState networkState,
                                     RouteCascade routeCascade,
                                     MAPPOTrainer trainer,
                                     int budgetLevels,
                                     double processingDelayMs,
                                     double simulationWindowMs,
                                     int queueCapacity,
                                     long evaluationSeed,
                                     boolean evaluateCheckpoints) {
        if (topology == null || networkState == null || routeCascade == null || trainer == null) {
            throw new IllegalArgumentException("Episode driver dependencies are required");
        }
        if (budgetLevels <= 0) throw new IllegalArgumentException("Budget levels must be positive");
        this.topology = topology;
        this.networkState = networkState;
        this.routeCascade = routeCascade;
        this.trainer = trainer;
        this.budgetLevels = budgetLevels;
        this.processingDelayMs = processingDelayMs;
        this.simulationWindowMs = simulationWindowMs;
        this.queueCapacity = queueCapacity;
        this.evaluationSeed = evaluationSeed;
        this.evaluateCheckpoints = evaluateCheckpoints;
    }

    public TrainingRunResult train(List<TrafficFlow> requestDataset,
                                   int episodes,
                                   int horizon,
                                   int minibatchSize,
                                   int updateRounds) {
        if (requestDataset == null || requestDataset.isEmpty()) {
            throw new IllegalArgumentException("At least one flow request is required");
        }
        if (episodes <= 0 || horizon <= 0 || horizon > requestDataset.size()) {
            throw new IllegalArgumentException("Episodes and horizon must be positive, and horizon "
                    + "must not exceed available requests (" + requestDataset.size() + ")");
        }
        List<TrafficFlow> episodeRequests = new ArrayList<>(requestDataset.subList(0, horizon));
        List<EpisodeSummary> summaries = new ArrayList<>();
        Map<Integer, MAPPOEpisodeResult> finalResults = Collections.emptyMap();

        for (int episodeIndex = 0; episodeIndex < episodes; episodeIndex++) {
            // The environment's persistent resource state is the admission
            // reservation list. Model parameters and RNG streams are retained.
            routeCascade.clearAdmittedAllocations();
            TrajectoryBuffer rollout = new TrajectoryBuffer();
            Map<Integer, MAPPOEpisodeResult> episodeResults = new LinkedHashMap<>();

            for (int requestIndex = 0; requestIndex < episodeRequests.size(); requestIndex++) {
                TrafficFlow flow = episodeRequests.get(requestIndex);
                TrajectoryStep.BoundaryType boundary = requestIndex == episodeRequests.size() - 1
                        ? TrajectoryStep.BoundaryType.TRUNCATED
                        : TrajectoryStep.BoundaryType.NONE;
                TrafficFlow nextRequest = requestIndex + 1 < episodeRequests.size()
                        ? episodeRequests.get(requestIndex + 1) : null;
                MAPPOEpisodeResult result = new MAPPOEpisode(
                        topology, networkState, routeCascade, trainer, budgetLevels)
                        .run(flow, episodeRequests, Collections.<Integer, Double>emptyMap(),
                                boundary, nextRequest);
                episodeResults.put(flow.getFlowId(), result);
                rollout.getSteps().addAll(result.getTrajectory().getSteps());
            }

            int collectedTransitions = rollout.size();
            MAPPOTrainer.TrainingStats stats = trainer.train(
                    rollout, minibatchSize, updateRounds);
            // Each episode owns a fresh buffer; clear explicitly after training
            // to make the boundary and lifetime visible in this implementation.
            rollout.clear();

            PacketSimulationResult checkpointSimulation = null;
            if (evaluateCheckpoints) {
                // Reset route reservations before checkpoint evaluation
                routeCascade.clearAdmittedAllocations();
                EvaluationRunResult evalRun = evaluate(episodeRequests, horizon, evaluationSeed);
                checkpointSimulation = runEvaluationSimulation(evalRun.getRequests(), evalRun.getResults());
                // Reset route reservations after checkpoint evaluation to prevent state leakage
                routeCascade.clearAdmittedAllocations();
            }

            summaries.add(new EpisodeSummary(episodeIndex + 1,
                    episodeRequests.size(), collectedTransitions,
                    stats.withoutSamplingTrace(), checkpointSimulation));
            finalResults = Collections.unmodifiableMap(new LinkedHashMap<>(episodeResults));
        }
        return new TrainingRunResult(summaries, finalResults, episodeRequests);
    }

    private PacketSimulationResult runEvaluationSimulation(
            List<TrafficFlow> requests,
            Map<Integer, MAPPOEpisodeResult> results) {
        List<PacketSimulator.FlowInput> admittedFlowInputs = new ArrayList<>();
        for (TrafficFlow flow : requests) {
            MAPPOEpisodeResult result = results.get(flow.getFlowId());
            if (result != null && result.isRouteComplete() && result.isRouteQualified()) {
                admittedFlowInputs.add(new PacketSimulator.FlowInput(flow, result));
            }
        }
        double windowMs = simulationWindowMs > 0.0 ? simulationWindowMs : 10.0;
        double procDelay = processingDelayMs >= 0.0 ? processingDelayMs : networkState.getProcessingDelay();
        int qCap = queueCapacity > 0 ? queueCapacity : 256;
        PacketSimulator simulator = new PacketSimulator(
                topology, admittedFlowInputs, procDelay,
                windowMs, qCap);
        return simulator.simulate();
    }

    /**
     * Runs a distinct final-policy evaluation pass. It resets episode reservations,
     * samples actions from an evaluation-only seeded RNG, and never calls train().
     */
    public EvaluationRunResult evaluate(List<TrafficFlow> requestDataset,
                                        int horizon,
                                        long evaluationSeed) {
        if (requestDataset == null || requestDataset.isEmpty()) {
            throw new IllegalArgumentException("At least one flow request is required");
        }
        if (horizon <= 0 || horizon > requestDataset.size()) {
            throw new IllegalArgumentException("Evaluation horizon must be positive and no greater "
                    + "than available requests (" + requestDataset.size() + ")");
        }
        List<TrafficFlow> requests = new ArrayList<>(requestDataset.subList(0, horizon));
        routeCascade.clearAdmittedAllocations();
        Random evaluationRandom = new Random(evaluationSeed);
        Map<Integer, MAPPOEpisodeResult> results = new LinkedHashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            TrafficFlow flow = requests.get(i);
            TrajectoryStep.BoundaryType boundary = i == requests.size() - 1
                    ? TrajectoryStep.BoundaryType.TRUNCATED
                    : TrajectoryStep.BoundaryType.NONE;
            TrafficFlow nextRequest = i + 1 < requests.size() ? requests.get(i + 1) : null;
            MAPPOEpisodeResult result = new MAPPOEpisode(
                    topology, networkState, routeCascade, trainer, budgetLevels,
                    evaluationRandom).run(flow, requests, Collections.<Integer, Double>emptyMap(),
                            boundary, nextRequest);
            results.put(flow.getFlowId(), result);
        }
        return new EvaluationRunResult(evaluationSeed, requests, results);
    }

    public static final class EpisodeSummary {
        private final int episodeNumber;
        private final int requestsProcessed;
        private final int transitionsCollected;
        private final MAPPOTrainer.TrainingStats trainingStats;
        private final PacketSimulationResult evaluationSimulationResult;

        public EpisodeSummary(int episodeNumber, int requestsProcessed,
                              int transitionsCollected,
                              MAPPOTrainer.TrainingStats trainingStats) {
            this(episodeNumber, requestsProcessed, transitionsCollected, trainingStats, null);
        }

        public EpisodeSummary(int episodeNumber, int requestsProcessed,
                              int transitionsCollected,
                              MAPPOTrainer.TrainingStats trainingStats,
                              PacketSimulationResult evaluationSimulationResult) {
            this.episodeNumber = episodeNumber;
            this.requestsProcessed = requestsProcessed;
            this.transitionsCollected = transitionsCollected;
            this.trainingStats = trainingStats;
            this.evaluationSimulationResult = evaluationSimulationResult;
        }

        public int getEpisodeNumber() { return episodeNumber; }
        public int getRequestsProcessed() { return requestsProcessed; }
        public int getTransitionsCollected() { return transitionsCollected; }
        public MAPPOTrainer.TrainingStats getTrainingStats() { return trainingStats; }
        public PacketSimulationResult getEvaluationSimulationResult() { return evaluationSimulationResult; }

        public int getGeneratedPackets() {
            return evaluationSimulationResult == null ? 0 : evaluationSimulationResult.getTotalPackets();
        }

        public int getDeliveredPackets() {
            return evaluationSimulationResult == null ? 0 : evaluationSimulationResult.getDeliveredPackets();
        }

        public int getTimelyPackets() {
            return evaluationSimulationResult == null ? 0 : evaluationSimulationResult.getPacketsDeliveredBeforeDeadline();
        }

        public int getLatePackets() {
            return evaluationSimulationResult == null ? 0 : evaluationSimulationResult.getPacketsMissingDeadline();
        }

        public int getDroppedPackets() {
            return evaluationSimulationResult == null ? 0 : evaluationSimulationResult.getDroppedPackets();
        }

        public double getTimelyDeliveryRatio() {
            return evaluationSimulationResult == null ? 0.0 : evaluationSimulationResult.getTimelyDeliveryRatio();
        }

        public double getAverageEndToEndDelay() {
            return evaluationSimulationResult == null ? 0.0 : evaluationSimulationResult.getAverageEndToEndDelay();
        }
    }

    public static final class TrainingRunResult {
        private final List<EpisodeSummary> episodeSummaries;
        private final Map<Integer, MAPPOEpisodeResult> finalEpisodeResults;
        private final List<TrafficFlow> requests;

        private TrainingRunResult(List<EpisodeSummary> episodeSummaries,
                                  Map<Integer, MAPPOEpisodeResult> finalEpisodeResults,
                                  List<TrafficFlow> requests) {
            this.episodeSummaries = Collections.unmodifiableList(new ArrayList<>(episodeSummaries));
            this.finalEpisodeResults = finalEpisodeResults;
            this.requests = Collections.unmodifiableList(new ArrayList<>(requests));
        }

        public List<EpisodeSummary> getEpisodeSummaries() { return episodeSummaries; }
        /**
         * Decisions from the last training rollout, collected before that
         * episode's optimizer update. Use evaluate() for final-policy results.
         */
        public Map<Integer, MAPPOEpisodeResult> getFinalEpisodeResults() { return finalEpisodeResults; }
        public List<TrafficFlow> getRequests() { return requests; }
    }

    public static final class EvaluationRunResult {
        private final long evaluationSeed;
        private final List<TrafficFlow> requests;
        private final Map<Integer, MAPPOEpisodeResult> results;

        private EvaluationRunResult(long evaluationSeed, List<TrafficFlow> requests,
                                    Map<Integer, MAPPOEpisodeResult> results) {
            this.evaluationSeed = evaluationSeed;
            this.requests = Collections.unmodifiableList(new ArrayList<>(requests));
            this.results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
        }
        public long getEvaluationSeed() { return evaluationSeed; }
        public List<TrafficFlow> getRequests() { return requests; }
        public Map<Integer, MAPPOEpisodeResult> getResults() { return results; }
    }
}
