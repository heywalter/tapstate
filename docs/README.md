# Documentation

| Start here | |
|---|---|
| [Quickstart](quickstart-online.md) | Bring up the stack and run a real MySQL to MongoDB sync, snapshot then CDC. |
| [Tutorials](tutorials/) | Worked scenarios, each with its own sample data, run end to end. |

| Features | |
|---|---|
| [Nest](nest/) | Assemble one document out of many tables and keep it current. |

## How this directory is laid out

Two shelves, because a reader arrives with one of two questions.

- **"Teach me"** goes to `tutorials/` - one directory per tutorial, holding the walkthrough and
  everything it needs. See [its README](tutorials/README.md) for what a new one has to do.
- **"I am using X and need to know Y"** goes to `<feature>/` - one directory per feature, with a
  `README.md` that says what the feature is and which page answers what.

A page's directory is what scopes it, so filenames do not repeat the feature name: it is
`nest/throughput-limits.md`, not `nest/nest-throughput-limits.md`. Anything that would sit loose at
this level either belongs to a feature or is a starting point, and there are only ever a few of
those.
