package traffic;

public class Packet {

    private int packetId;
    private int flowId;
    private double deadline;
    private double creationTime;
    private int size;
    private int hopIndex;

    private double singleHopDeadline;
    private double deadlineRank;
    private double initialRankTime;

    public Packet(
            int packetId,
            int flowId,
            double deadline,
            double creationTime,
            int size,
            int hopIndex,
            double singleHopDeadline) {

        this(packetId, flowId, deadline, creationTime, size, hopIndex,
                singleHopDeadline, creationTime);
    }

    /**
     * Creates a packet whose user-level release time and first-hop rank origin
     * may differ. For endpoint-aware simulation, creationTime is the user
     * release time while initialRankTime is the packet's arrival time at the
     * source access router. The legacy constructor keeps both times equal.
     */
    public Packet(
            int packetId,
            int flowId,
            double deadline,
            double creationTime,
            int size,
            int hopIndex,
            double singleHopDeadline,
            double initialRankTime) {

        this.packetId = packetId;
        this.flowId = flowId;
        this.deadline = deadline;
        this.creationTime = creationTime;
        this.size = size;
        this.hopIndex = hopIndex;
        this.singleHopDeadline = singleHopDeadline;
        this.initialRankTime = initialRankTime;

        if (hopIndex == 0) {
            // Eq. (3) - At hop 1, the rank is ingress arrival time plus the local budget.
            this.deadlineRank =
                    initialRankTime + singleHopDeadline;
        }
    }

    public int getPacketId() {
        return packetId;
    }

    public int getFlowId() {
        return flowId;
    }

    public double getDeadline() {
        return deadline;
    }

    public double getCreationTime() {
        return creationTime;
    }

    public int getSize() {
        return size;
    }

    public int getHopIndex() {
        return hopIndex;
    }

    public double getSingleHopDeadline() {
        return singleHopDeadline;
    }

    public double getDeadlineRank() {
        return deadlineRank;
    }

    public void updateDeadlineRank() {

        if (hopIndex == 0) {
            // Eq. (3) - Initialize the first-hop deadline from arrival and local delta.
            deadlineRank =
                    initialRankTime + singleHopDeadline;
        } else {
            // Eqs. (3)-(4) - Add this hop's local delta to obtain the cumulative rank through this hop.
            deadlineRank =
                    deadlineRank + singleHopDeadline;
        }
    }

    public void moveToNextHop(double nextSingleHopDeadline) {

        hopIndex++;

        singleHopDeadline = nextSingleHopDeadline;

        updateDeadlineRank();
    }

    @Override
    public String toString() {

        return "Packet " + packetId +
                " | Flow " + flowId +
                " | Hop = " + hopIndex +
                " | Deadline Rank = " +
                deadlineRank +
                " ms | Size = " +
                size +
                " bytes";
    }
}
