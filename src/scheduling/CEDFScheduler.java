package scheduling;

import java.util.ArrayList;
import java.util.List;
import traffic.Packet;

public class CEDFScheduler {

    private List<Packet> queue;
    private int queueCapacity;

    public CEDFScheduler(int queueCapacity) {

        this.queueCapacity = queueCapacity;
        this.queue = new ArrayList<>();
    }

    public boolean enqueuePacket(Packet packet) {
        // Algorithm 1 ingress: a full waiting queue drops the incoming packet.
        if (queue.size() >= queueCapacity) {
            return false;
        }

        for (int i = 0; i < queue.size(); i++) {

            Packet currentPacket = queue.get(i);

            // Algorithm 1 ingress: insert before the first existing rank q > the new rank r (FIFO on ties).
            if (currentPacket.getDeadlineRank()
                    > packet.getDeadlineRank()) {

                queue.add(i, packet);

                return true;
            }
        }

        queue.add(packet);

        return true;
    }

    public Packet selectNextPacket() {

        if (queue.isEmpty()) {
            return null;
        }

        return queue.remove(0);
    }

    public boolean hasPackets() {

        return !queue.isEmpty();
    }

    public int getQueueSize() {

        return queue.size();
    }

    public boolean isFull() {

        return queue.size() >= queueCapacity;
    }

    public int getQueueCapacity() {

        return queueCapacity;
    }
}
