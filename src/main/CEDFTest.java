import scheduling.CEDFScheduler;
import traffic.Packet;

public class CEDFTest {

    public static void main(String[] args) {
        testIncreasingRankAndEqualRankInsertion();
        testDispatchedPacketIsNotPreempted();
        System.out.println("CEDFTest passed: rank order, q>r FIFO equal-rank insertion, and non-preemption.");
    }

    private static void testIncreasingRankAndEqualRankInsertion() {
        CEDFScheduler scheduler = new CEDFScheduler(5);
        Packet rank5 = packet(1, 5.0);
        Packet rank3 = packet(2, 3.0);
        Packet firstRank4 = packet(3, 4.0);
        Packet laterRank4 = packet(4, 4.0);

        check(scheduler.enqueuePacket(rank5), "enqueue rank 5");
        check(scheduler.enqueuePacket(rank3), "enqueue rank 3");
        check(scheduler.enqueuePacket(firstRank4), "enqueue first rank 4");
        check(scheduler.enqueuePacket(laterRank4), "enqueue equal rank 4");

        // Algorithm 1 inserts a new packet before q when q > r. For equal ranks,
        // packets already queued remain ahead, preserving FIFO order.
        assertPacket(2, scheduler.selectNextPacket(), "lowest rank first");
        assertPacket(3, scheduler.selectNextPacket(), "first-arrived equal-rank packet served first (FIFO)");
        assertPacket(4, scheduler.selectNextPacket(), "later-arrived equal-rank packet follows (FIFO)");
        assertPacket(1, scheduler.selectNextPacket(), "highest rank last");
        check(!scheduler.hasPackets(), "queue empty after all packets selected");
    }

    private static void testDispatchedPacketIsNotPreempted() {
        CEDFScheduler scheduler = new CEDFScheduler(3);
        Packet active = packet(10, 5.0);
        Packet waiting = packet(11, 7.0);
        Packet urgentArrival = packet(12, 1.0);
        check(scheduler.enqueuePacket(active), "enqueue active candidate");
        check(scheduler.enqueuePacket(waiting), "enqueue waiting packet");

        Packet inService = scheduler.selectNextPacket();
        check(inService == active, "rank-5 packet is dispatched first");
        check(scheduler.enqueuePacket(urgentArrival), "enqueue more urgent packet during service");

        // The scheduler owns only waiting packets; an already returned packet
        // remains in service while the urgent arrival becomes next in queue.
        check(inService == active, "earlier rank does not preempt the dispatched packet");
        assertPacket(12, scheduler.selectNextPacket(), "urgent arrival is next after service completes");
        assertPacket(11, scheduler.selectNextPacket(), "previously waiting packet follows");
    }

    private static Packet packet(int id, double rank) {
        return new Packet(id, 1, 20.0, 0.0, 1000, 0, rank);
    }

    private static void assertPacket(int expectedId, Packet actual, String message) {
        check(actual != null && actual.getPacketId() == expectedId,
                message + ": expected packet " + expectedId + ", got "
                        + (actual == null ? "null" : actual.getPacketId()));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
