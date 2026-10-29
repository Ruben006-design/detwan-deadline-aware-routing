# Java packet-simulation delay model

The current Java simulator models one CEDF waiting queue and one busy state per
directed egress link. At dispatch, a packet incurs the configured router
processing delay and then occupies the egress for its serialization time
(`packet bits / link bits per millisecond`). Propagation delay begins after
serialization completes. The egress may start its next packet at transmission
completion; propagation is in flight and does not keep the egress busy.

Therefore, **processing and transmission are serialized per egress** in this
project model. Independent output links can progress concurrently. The IEEE
paper specifies processing and propagation delays and link service capacity,
but the reviewed text does not settle whether packet processing overlaps link
transmission or is serialized per egress. This implementation choice should
not be presented as a verified paper requirement.

For each packet, the first absolute CEDF rank is its creation time plus the
first local single-hop deadline budget. On each subsequent hop, the next
single-hop budget is added to the cumulative rank. CEDF keeps waiting packets
in ascending rank order; Algorithm 1's `q >= r` condition means a newly
inserted packet precedes an existing packet with the same rank. A packet
already dispatched to service is not preempted.

The simulator's deterministic 1000-byte burst-plus-rate workload, finite
waiting-queue capacity, and packet TDR denominator are project assumptions;
they do not reproduce the paper's full CEDF experiment.
