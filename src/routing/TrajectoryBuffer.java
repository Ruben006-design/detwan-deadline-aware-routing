package routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TrajectoryBuffer {

    private List<TrajectoryStep> steps;

    public TrajectoryBuffer() {
        steps = new ArrayList<>();
    }

    public void add(TrajectoryStep step) {
        steps.add(step);
    }

    public TrajectoryStep get(int index) {
        return steps.get(index);
    }

    public int size() {
        return steps.size();
    }

    public List<TrajectoryStep> getSteps() {
        return steps;
    }

    /**
     * Returns each router's transitions in their original temporal order.
     * MAPPO agents must not share a single GAE recursion merely because their
     * actions were collected during the same route cascade.
     */
    public Map<Integer, TrajectoryBuffer> getAgentTrajectories() {
        Map<Integer, TrajectoryBuffer> grouped = new LinkedHashMap<>();
        for (TrajectoryStep step : steps) {
            grouped.computeIfAbsent(step.getAgentId(), key -> new TrajectoryBuffer())
                    .add(step);
        }
        return Collections.unmodifiableMap(grouped);
    }

    public void clear() {
        steps.clear();
    }
}
