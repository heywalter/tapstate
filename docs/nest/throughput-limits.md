# Nest throughput limits

A nest assembles rows from several tables into one document. The unit it sends is the
**whole document**, so a change to any row feeding a root rewrites that root in full.
This page is how you work out, before you run it, whether your data shape fits.

Everything below is arithmetic you can do on your own numbers. Where a number came from
a measurement, it says so and says on what.

## How often a document is sent

Sending is on a leading-edge window, 50 ms by default:

| when | what happens |
|---|---|
| a document changes and no window is open | it goes out **at once**, and a window opens behind it |
| it changes again inside that window | the change is folded into what goes out at the end of it |
| the window ends with something folded in | one document goes out, carrying everything folded, and a window opens again |
| the window ends with nothing folded in | the window closes; the next change is a leading edge again |

Two consequences worth reading twice:

- **A root changing less often than the window pays nothing.** There is no added latency
  for a quiet document - it goes out on its own leading edge.
- **A hot root's send rate is capped, not scaled.** Whatever its change rate, one root is
  sent at most `1000 / window` times a second.

Measured, one root, default 50 ms window:

| changes/s | sends/s | changes per send |
|---|---|---|
| 50 | 20.5 | 2.4 |
| 100 | 20.5 | 4.9 |
| 200 | 20.5 | 9.6 |
| 400 | 21.0 | 18.4 |
| 800 | 20.5 | 35.9 |

The send rate is flat at `1000/window`; **how much folding buys you is a property of your
change rate, not of the window.** A tenfold reduction is what a root taking about 200
changes a second sees. One taking 50 sees rather less, one taking 800 rather more.

**A deletion is never folded.** It goes out immediately and ends the window, because the
key it names can be gone before the window is.

**An append root folds nothing at all.** There every send is a new record, so merging two
versions would lose one rather than save a write. Under `mode: append` every change is
sent on its own, whatever the window is set to.

## What a nest promises its reader

**A nest emits a final-state changelog. It does not promise that every version is visible.**

This is a contract, not an implementation detail: folding is safe precisely because what
goes out is a state rather than a change, and a reader that applies it by key lands in the
same place whether it saw one version or ten.

| reader | supported |
|---|---|
| a target upserted on the document key | yes |
| a stateless `map` / `filter` step, then an upserting target | yes - the end state agrees, even where an intermediate version would have been filtered out |
| another `nest` downstream, taking this output as a child table | yes - it converges on the same end state |
| **an append-only target, or an audit that must see every version** | **no - not a supported combination** |

If you need every version, set the root's `mode: append`, which turns folding off entirely
for that nest. What this is judged on is that root's own mode and nothing else: a nest does
not look downstream to work out whether something further along wanted every version.

## The four limits

Each of these answers **"how much does my data shape ask for"**. None of them is a promise
about what the system delivers - three of them cannot even be measured, because they follow
from the definitions. Compare them against what your storage and network can actually do.

### 1. One root, sink side

```
max sustained output of one root  ~=  whole-document bytes / window
```

Example: a 2 MB document with a 50 ms window asks for about 40 MB/s.

The window is the only term here you can turn. **This is the one the throttle reduces.**

The output is not one round trip per document: the sink applies one batch at a time and
takes up to 1024 documents per batch, so under a backlog the round-trip cost is spread
across the batch rather than paid per document. Measured, with one write in flight:

| documents queued | write calls | largest batch |
|---|---|---|
| 1 | 1 | 1 |
| 16 | 1 | 16 |
| 256 | 1 | 256 |
| 4096 | 4 | 1024 |

### 2. One root, state side

```
max sustained output of one root  ~=  state write bandwidth / whole-document bytes
```

**The throttle does not reduce this one.** Sending is what the window rations; the state
has to be updated by every change, or the state is wrong. Each update is written through to
durable storage as it happens.

Same 2 MB document taking 200 changes a second: the state side is being asked for about
400 MB/s, and no window setting changes that.

Measured, same runs as the folding table above:

| changes/s | state writes per change |
|---|---|
| 50 | 1.40 |
| 100 | 1.20 |
| 200 | 1.10 |
| 400 | 1.05 |
| 800 | 1.02 |

Slightly **above** one per change, not below: the count is `changes + windows flushed`,
because a flush stores the document once more after releasing what it carried. The window
therefore costs a little more on the state side rather than less, and the ratio approaches
1.0 as the change rate rises, since the flushes are capped while the changes are not.

### 3. The whole pipeline, bandwidth

```
aggregate state write bandwidth  ~=  total event rate x average whole-document bytes
```

Example: 5,000 events/s against 100 KB documents asks for about 500 MB/s.

**This one holds with no hot root anywhere.** It is the same multiplication as (2) applied
across every root at once, so a pipeline whose roots are all individually modest can still
reach it. If you read only (1) and (2) you would conclude "I have no hot roots, so this does
not apply to me", and that conclusion is wrong.

### 4. The whole pipeline, writes per second

```
aggregate durable writes per second  ~=  total event rate x (average hops crossed + 1)
```

Example: 5,000 events/s through a tree of depth 4, where a leaf row crosses about 3 levels,
asks for about 20,000 durable writes a second.

This one is **counts, not bytes**, and it is the one most often missed:

- **It does not grow with document size.** It grows with **depth**. A pipeline of small
  documents in a deep tree can be comfortably inside limits 1 to 3 while already past
  this one.
- **It lands on a different hardware limit.** Small synchronous writes: roughly 10^4 to
  10^5 per second on local NVMe, one to two orders of magnitude lower on network block
  storage or spinning disks. **This limit, not the bandwidth ones, is usually what decides
  which disk you need.**
- **There is no knob.** The throttle does not reach it, and depth is a property of your
  data rather than a setting.

Measured against a real database on one development machine: a durable state write costs
about the same at 64 B as at 100 KB - the commit dominates and the bytes barely register -
which is why this limit is expressed as a count. Above roughly 100 KB per document the byte
term starts to matter and limits 2 and 3 take over.

## What is not on this page, and will not be

**How much your target can absorb.** Tapstate does not publish a figure for it, and that is a
decision rather than a gap: the write capability of the database, queue or table a nest feeds
belongs to your deployment - its hardware, its schema, its indexes, its other load - and a
number measured against someone else's would be read as a promise about yours. **This is the
one input to the four limits above that you supply and we do not.**

Measure it the way you would measure any target: apply the write your sink will be applying,
at the size your documents actually are, and see what it sustains. Then put that number into
limits 1 to 4 - they are written so that your number is the only thing missing.

Two properties of the sink side that are ours to state, and are stated above: **one batch is
in flight at a time**, and **a batch carries up to 1024 documents**, so the cost of a round
trip falls as the backlog grows rather than being paid per document.

**Absolute throughput numbers generally.** What one durable write costs on your storage is a
property of your deployment for the same reason. The measurements on this page are ratios and
counts, which travel; a documents-per-second figure from someone else's machine does not.

## Settings

| setting | default | effect |
|---|---|---|
| send window | 50 ms | how long after a document is sent before the next version of it may be. `0` sends every version as it is assembled. |

Raising the window folds more and caps a hot root lower, at the cost of a document being at
most that old when a reader looks. Lowering it towards zero removes the cap: the send rate
of a hot root becomes its change rate.

Two things the window cannot help with: limits 2, 3 and 4 above, and a root under
`mode: append`, which is never folded.
