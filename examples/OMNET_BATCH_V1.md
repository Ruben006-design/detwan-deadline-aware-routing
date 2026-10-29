# DetWAN Java to OMNeT++ batch contract (v1)

The exporter writes UTF-8 comma-separated records. Quoted fields use normal CSV escaping; `;` separates items inside route, budget, and arrival-list fields.

Record order is fixed:

1. `FORMAT,DET-WAN-OMNET-BATCH,1`
2. `TOPOLOGY_ID,<stable id>`
3. `TOPOLOGY_FINGERPRINT_SHA256,<Java topology fingerprint>`
4. `PROCESSING_DELAY_MS,<milliseconds>`
5. `GENERATION_WINDOW_MS,<milliseconds>`
6. `PACKET_SIZE_BYTES,<bytes>`
7. `UNITS,...`
8. One `LINK,source,destination,bandwidth_mbps,propagation_delay_ms` row for every link used in a selected route.
9. The `FLOW,...` header, followed by one row per considered request.

Flow columns are `flow_id,source,destination,rate_mbps,burst_bytes,deadline_ms,admission_status,rejection_reason,route_nodes,local_hop_budgets_ms,packet_arrivals_ms,packet_size_bytes`. Route node count must be hop count plus one; local budget count must equal hop count. These are per-hop δ values, not cumulative CEDF rank B. A rejected request remains in the file with its exact rejection/termination text, but has no packet-arrival schedule and is not simulated.

Packet arrivals make the Java project workload explicit: 1000-byte packets; `floor(burst_bytes/1000)` packets at time zero; then `floor(rate_mbps*125*window_ms/1000)` packets at offsets `i*1000/(rate_mbps*125)` ms, for i starting at one. This is a project workload assumption, not a paper-specified arrival model. Rate uses decimal Mbps, burst uses decimal bytes (1 KB = 1000 bytes), deadline, processing, propagation, δ, and arrival offsets use ms. Link capacities are exported in Mbps.

Versions 1 and 2 do not carry source or destination endpoint access delays. The Java exporter rejects a nonzero delay when asked to write either legacy version. Version 3 adds those values without changing either legacy schema.

`TOPOLOGY_ID` is the compatibility gate configured by the OMNeT++ run; the fingerprint is diagnostic metadata. OMNeT++ must also validate each exported used link against the configured directed channel parameters and validate each admitted route. The illustrative CSV's fingerprint is explicitly a placeholder and is not a runnable export.

The Java exporter refuses to overwrite output files. Use a new path for every export. `Main --export-omnet <directory>` runs the existing MAPPO and RouteCascade path, trains as it currently does, writes each seed's decisions there, and skips Java `PacketSimulator`.

## Version 2 full-topology extension

Version 2 retains all version 1 metadata and FLOW columns and inserts a complete topology inventory after `UNITS`:

1. `FORMAT,DET-WAN-OMNET-BATCH,2`
2. Existing metadata rows through `UNITS`.
3. `NODE,node_id`, followed by exactly one `NODE,<node_id>` row for every topology node.
4. `LINK,source,destination,bandwidth_mbps,propagation_delay_ms`, followed by every directed link, including links unused by the exported routes.
5. The unchanged FLOW header and flow records.

Rows are sorted by node name and by `source->destination` for deterministic output. Rates/capacities use decimal Mbps; burst is decimal bytes; timing and local single-hop δ budgets are milliseconds. Route and budget ordering remains hop order. Cumulative CEDF rank B is not exported because OMNeT++ accumulates it from the imported per-hop δ values at each egress.

The topology identifier `abilene-sndlib-12n15l-v1` names the 12-node, 15-physical-link SNDlib variant, represented here as 30 directed links. It retains ATLAM5. It is a topology variant and is **not confirmed to match** the paper's 11-node, 14-link Abilene graph. The Java loader's configured propagation delay is 2 ms; OMNeT++ integrated runs also use 0.05 ms per-router processing. These are project settings and are not confirmed as the paper's complete Abilene parameters.

## Version 3 endpoint-access extension

Version 3 retains the complete V2 node/link inventory, metadata, and all V2 FLOW columns in the same order, then appends exactly two columns to each FLOW row:

`source_access_delay_ms,destination_access_delay_ms`

The V3 FLOW header is `FLOW,flow_id,source,destination,rate_mbps,burst_bytes,deadline_ms,admission_status,rejection_reason,route_nodes,local_hop_budgets_ms,packet_arrivals_ms,packet_size_bytes,source_access_delay_ms,destination_access_delay_ms`.

The V3 UNITS row appends `source_access_delay=ms;destination_access_delay=ms`. Each endpoint value must be finite and in the inclusive range `[0,1]` ms; the Java exporter and OMNeT++ importer reject negative and over-limit values. Zero-delay V1/V2 batches remain supported with their original columns and are interpreted as zero endpoint delay.

Timing semantics: packet creation/CSV arrival is the user-side release time and the absolute deadline is release plus `deadline_ms`. Source access delay is applied once before the packet reaches its source router. Destination access delay is applied once after it reaches the destination router and before delivery/deadline classification. CEDF's intermediate-hop cumulative rank still adds only the exported local δ values; endpoint delays do not change those δ increments. No OMNeT++ admission pass is run for imported flows; Java's admission status remains authoritative.