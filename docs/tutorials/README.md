# Tutorials

Worked scenarios you can run end to end. Each one loads its own sample data, builds something real,
and says what to look at afterwards. They assume the stack from the
[online quickstart](../quickstart-online.md) is up.

| Tutorial | What you build | Time |
|---|---|---|
| [Assembling one document out of many tables](nest-document-assembly/) | Three live documents - order, customer and product - assembled from the same nine relational tables with a `nest` transform and kept current by change data capture. Includes the rule that decides which document shapes a nest can express. | ~30 min |

## Adding a tutorial

**One directory per tutorial, and everything it needs lives inside it.**

```
docs/tutorials/
├── README.md                     <- this index
└── <tutorial-slug>/
    ├── README.md                 <- the walkthrough itself
    ├── <data>.sql                <- sample data, if it has any
    └── <other assets>            <- workspace files, diagrams, scripts
```

The directory name is the slug a reader sees in the URL, so name it for the thing being taught
(`nest-document-assembly`), not for the page (`tutorial-3`). Putting the walkthrough in `README.md`
means a link to the directory renders it.

Two rules the existing one follows and the next one should too:

- **Sample data is generated deterministically** - no random values, no timestamps of the day it ran.
  A tutorial quotes row counts and document shapes in its text, and those numbers have to be the ones
  the reader sees. Seed with a numbers table and arithmetic, not with `RAND()`.
- **Say what does not work, not only what does.** The failure a reader would otherwise ship is worth
  more space than the happy path, especially where the product accepts something and goes wrong
  quietly rather than refusing it.
