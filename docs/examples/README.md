# Pipeline examples

- `parallel-ingestion.json` fans a parsed text document into two independent `static-splitter` nodes. Those nodes declare `parallel-safe`, so the executor may run that ready stage concurrently, bounded by `PIPELINE_EXECUTION_MAX_PARALLELISM`.
- `fan-in-ingestion.json` shows two branches joining at `document-merge`. A node with more than one incoming edge must declare the `fan-in` capability and implement `FanInPlugin`.

Import either definition through the Studio or `POST /api/v1/pipelines/import`, then publish it before starting an execution. The source file must be under `PIPELINE_INPUT_ROOT`.
