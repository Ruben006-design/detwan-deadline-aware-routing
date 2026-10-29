package routing;

public class TrajectoryStep {

    /** Distinguishes a true absorbing terminal state from a finite rollout cut. */
    public enum BoundaryType {
        NONE,
        TERMINATED,
        TRUNCATED
    }

    private int agentId;
    private double[] globalState;
    private double[] observation;
    private int actionIndex;
    private double actionProbability;
    private double reward;
    private double[] nextGlobalState;
    private boolean[] actionMask;
    private BoundaryType boundaryType;
    private boolean active;

    public TrajectoryStep(
            int agentId,
            double[] globalState,
            double[] observation,
            int actionIndex,
            double actionProbability,
            double reward,
            double[] nextGlobalState) {

        this(agentId, globalState, observation, actionIndex, actionProbability,
                reward, nextGlobalState, null, false, true);
    }

    public TrajectoryStep(
            int agentId,
            double[] globalState,
            double[] observation,
            int actionIndex,
            double actionProbability,
            double reward,
            double[] nextGlobalState,
            boolean[] actionMask) {

        this(agentId, globalState, observation, actionIndex, actionProbability,
                reward, nextGlobalState, actionMask, false, true);
    }

    public TrajectoryStep(
            int agentId,
            double[] globalState,
            double[] observation,
            int actionIndex,
            double actionProbability,
            double reward,
            double[] nextGlobalState,
            boolean[] actionMask,
            boolean terminal) {

        this(agentId, globalState, observation, actionIndex, actionProbability,
                reward, nextGlobalState, actionMask,
                terminal ? BoundaryType.TERMINATED : BoundaryType.NONE, true);
    }

    /**
     * Creates one agent's sample for a flow-request time step. Inactive samples
     * preserve the shared state/reward sequence, but do not represent an action.
     */
    public TrajectoryStep(
            int agentId,
            double[] globalState,
            double[] observation,
            int actionIndex,
            double actionProbability,
            double reward,
            double[] nextGlobalState,
            boolean[] actionMask,
            boolean terminal,
            boolean active) {

        this(agentId, globalState, observation, actionIndex, actionProbability,
                reward, nextGlobalState, actionMask,
                terminal ? BoundaryType.TERMINATED : BoundaryType.NONE, active);
    }

    /** Creates a transition with an explicit continuation/termination boundary. */
    public TrajectoryStep(
            int agentId,
            double[] globalState,
            double[] observation,
            int actionIndex,
            double actionProbability,
            double reward,
            double[] nextGlobalState,
            boolean[] actionMask,
            BoundaryType boundaryType,
            boolean active) {

        this.agentId = agentId;
        this.globalState = globalState.clone();
        this.observation = observation.clone();
        this.actionIndex = actionIndex;
        this.actionProbability = actionProbability;
        this.reward = reward;
        this.nextGlobalState = nextGlobalState.clone();
        this.actionMask = actionMask == null ? null : actionMask.clone();
        if (boundaryType == null) {
            throw new IllegalArgumentException("Transition boundary type cannot be null");
        }
        this.boundaryType = boundaryType;
        this.active = active;
    }

    public int getAgentId() { return agentId; }
    public double[] getGlobalState() { return globalState.clone(); }
    public double[] getObservation() { return observation.clone(); }
    public int getActionIndex() { return actionIndex; }
    public double getActionProbability() { return actionProbability; }
    public double getReward() { return reward; }
    public double[] getNextGlobalState() { return nextGlobalState.clone(); }
    /** True only for an absorbing environment terminal, not a rollout cutoff. */
    public boolean isTerminal() { return boundaryType == BoundaryType.TERMINATED; }
    /** True when collection stopped at a horizon while the environment can continue. */
    public boolean isTruncated() { return boundaryType == BoundaryType.TRUNCATED; }
    public BoundaryType getBoundaryType() { return boundaryType; }
    /** False when this agent was not on the selected route for this request. */
    public boolean isActive() { return active; }
    /** Null represents the legacy all-actions-valid behavior. */
    public boolean[] getActionMask() {
        return actionMask == null ? null : actionMask.clone();
    }
}
