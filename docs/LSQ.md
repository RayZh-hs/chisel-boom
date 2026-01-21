# LoadStoreQueue (LSQ)

Our LoadStoreQueue is partial out-of-order, and features store-to-load forwarding.
It contains a Load Queue and a Store Queue, and is connected via the LoadStoreAdaptor.

## Store Queue

the store queue is a circular buffer. Unlike the rest of IssueBuffer syste, it is a Reservation Station style buffer, meaning that it captures the data when broadcasted. The AGU(Address Generation Unit) in the SQ will calculate a effective address for an entry that the base is ready, so that store-to-load forwarding can be done.
When the addr and data are both ready, the store is marked as ready to commit. The SQ will first broadcast the entry to ROB for commit, then check the ROB if it is committed. If yes, this store can now be sent to DCache.

## Load Queue

The load queue is a non-ordered buffer. It issues load speculatively. It is also a RS style buffer. When a load enq, it records the SQ tail.The AGU logic is same. A load have 3 states: not-ready(waiting for addr), ready, waiting-for-store.
Every cycle, we pick a load that is ready, and check its collision with prior stores(prior is determined by the SQ tail recorded when enq). If there is a store which completely overlaps and have data, we forward the data from that store. If there is a store which partially overlaps or completely overlaps but not ready, we mark this load as waiting-for-store, record the store's ROB id, and listen for the store's commit broadcast. When we see the store is committed, we can now mark this load as ready again. If there is no collision, we can send this load to DCache.