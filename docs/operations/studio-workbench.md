# Studio workbench

Studio is the existing static HTML/CSS/JavaScript application served by Micronaut. The workbench uses Fluent-inspired controls and original local outline icons, with restrained surfaces and typography. It introduces no frontend framework, remote icon service, or external font dependency.

## Workspace

The command bar groups **AI Studio**, **Save draft**, **Publish**, and **Build app**. Use **AI Studio** to open plugin recommendations, then **Back to Studio** to return to the main workbench. Project name, saved-project selection, import/export, connection management, and chunk preview are directly below it. **Project overview** keeps catalog/execution metrics available without taking space from the canvas.

On wide screens, the catalog appears on the left, ingestion above query in the center, and the selected-node inspector on the right. **Catalog** and **Inspector** toggle their panels. On smaller screens, the panels open over the workspace. **View → Focus canvas** gives the workflows the full width. Escape closes an overlay panel or clears a selected output port.

Appearance follows the system and control spacing defaults to Comfortable. **View** also offers light/dark overrides and compact spacing. These preferences and node positions are stored locally; pipeline settings and secrets are not stored in local storage.

## Add, arrange, and connect

Search and the **All / Ingestion / Query** segments filter the plugin catalog together. Filtering does not hide either workflow. Drag a compatible plugin into a lane, or use its labeled Add action.

Every plugin and event adapter has an **Info** icon that opens a shared information dialog. Plugin details come from catalog metadata: purpose, version, workflow usage, input/output types, capabilities and configuration field descriptions. Adapter help explains its destination, subscriptions and hosting requirements. Local UI help distinguishes Studio's existing server from the optional generated web host and explains using Kafka alongside it. Info is also available on nodes, in the inspector, for adapter instances/settings, and beside generated-host settings. Opening help does not add, attach or configure anything, and configuration values/secrets are never displayed. Close it with its close button or Escape.

Nodes show their name, ports, and connection summary. Select a node or its inspector button to edit it. Drag its header to arrange it; a focused card also supports Alt + arrow keys. **Arrange** creates a compact layout without changing connections or pipeline configuration. Layout changes are local and do not mark the project draft as edited.

Connect an output port to a compatible input within the same workflow. The inspector's **Connections** section provides an accessible dropdown alternative and connection-removal controls. Type, input-cardinality, cycle, and workflow checks still apply. **Validate** checks the complete project through the existing backend validator.

## Settings and profiles

The inspector contains schema-generated configuration, profile selection, validation, connection testing, connection wiring, and an expandable execution-policy section. Existing plugin configuration capabilities, including nested objects, arrays, booleans, enums, and environment secret references, remain available.

For flat profile fields, the inspector identifies **Inherited** values and **Node override** values. **Reset to profile** removes the node override and displays the latest profile value. Nested objects follow the backend's whole-object override semantics: editing an inherited child copies the current profile object into the node, the group is labeled **Object override**, and its reset action removes the entire object override. Missing children in an already overridden object use schema defaults rather than silently inheriting profile siblings.

**Published settings** opens a read-only view of the selected node in the project's latest published version, when that node exists there. Drafts use live profile references; publications retain captured settings. Secret fields display environment-reference names, never resolved secret values. **Connections** opens the shared-profile editor without moving the canvas or its forms.

## Saving and publishing

Saving remains explicit, including Ctrl+S / Command+S when no dialog is open. The command bar distinguishes a new project, unsaved changes, and a saved draft revision. Changing projects or closing the page with unsaved draft edits triggers the normal unsaved-change warning.

**Publish** saves the current draft first and then publishes both workflow lanes, including their captured connection settings. If further edits arrive during that save, publishing waits for the user to save those edits rather than silently publishing an older canvas state.

## Run, trace, and build

A lane's **Run ingestion** or **Run query** action opens the matching inputs in the **Run and monitor** drawer. It never executes automatically using an input left from a previous run. The selected published version is displayed explicitly, and the separate **Run workflow** action starts execution. Uploads, existing input-root file paths, query text, event traces, recent executions, and cancellation use the existing APIs.

The trace shows its workflow, version, and status. **Follow log** keeps new events in view; scrolling away from the bottom turns following off so the reader can inspect older events. Execution history identifies the project/workflow/version where available.

**Build app** retains the separate build tab. Each populated workflow must be a connected linear chain for export. Build progress, downloads, destination validation, and JAR/native requirements are unchanged. The build log also offers Follow and Copy controls. JAR output remains a project ZIP containing its required relative `lib/` directory.

## Event adapters and live monitoring

Choose **Adapters** in the catalog to configure event observers. Add an adapter instance to the project, then attach it in a selected node's **Event adapters** inspector or use a workflow's **Events** button. Dragging an adapter onto a node attaches it to that node; dropping it onto a lane subscribes to the whole workflow. These attachments are separate from processing connections and do not add nodes or edges. Workflow subscriptions include workflow lifecycle events and all nodes. Node subscriptions include only their selected nodes. Removing the last node from a node subscription removes that subscription; it never widens the subscription to a whole workflow.

Observed nodes show a compact adapter dock below the card, connected by a dashed stem. Clicking the dock opens its adapter settings. Multiple observers show the first adapter name and an additional count; the inspector lists every attachment. Workflow-wide subscriptions also draw a dashed link from the lane identity to its **Events** control. These annotations have no processing ports or arrows and are never saved as processing edges. **Arrange** leaves extra space in lanes with observers, while manually placed nodes retain their positions.

**Monitoring** opens adapter configuration, workflow subscriptions, generated-host settings, and template selection. Event type and severity filters are optional; empty filters receive all matching events. Adapter configuration follows the catalog schema. Use environment secret references such as `secret://env/KAFKA_PASSWORD`; resolved credentials do not belong in a pipeline. **Test adapter** performs the backend connection check. Save the draft and publish to bind these settings to a run.

The **Command line** / **Micronaut web host** choice applies to generated applications. Studio already has a web host and can monitor executions regardless of that generation setting. Applications generated with a UI adapter require the web host. The configured HTTP port and optional browser opening apply to that generated application.

Open **Live monitor** from a trace or **Monitor** from execution history. The separate page shows the published workflow version, stage states, current progress, attempt history, adapter health, and a severity-filtered timeline. The **Standard** template uses stage cards. **Water flow** preserves graph branches and fan-in, uses real reported totals for water levels, and shows an indeterminate wave when a running stage has no total. Completion is shown as a status; it never replaces measured progress with a full bar or tank. Unknown totals have no percentage or fill, and reported partial counts remain visible after success. Retry begins a new attempt; earlier attempt details remain visible. Plugin messages cannot change lifecycle state. Timeline following can be paused while reading older events.

The monitor authenticates through the existing login endpoint and sends bearer tokens in request headers. Streaming uses fetch with a numeric sequence cursor, deduplicates replayed events, and reconnects after interruption. An expired cursor loads a fresh server snapshot. A completed execution can be reopened as history. The page retains at most 500 timeline entries and 25 attempts per node; the server's snapshot and retention policy determine which earlier history is available.

UI subscriptions project which event details are visible. Envelopes marked `visible: false` still advance the replay cursor and are omitted from the timeline. Hidden progress and plugin messages do not change displayed domain details. Hidden lifecycle events still update authoritative stage/run state and safe input/output summaries. Snapshot progress is the backend's projected value for the current attempt, so reconnect does not reveal filtered counts.

Task termination is recorded separately from the attempt outcome. A termination confirmation preserves a known success, failure, or timeout; a requested cancellation can become cancelled. This keeps worker shutdown events from rewriting an already completed stage's result. Health refreshes every ten seconds while the page is visible without resetting its event cursor or timeline.

Health rows include actual queued, delivered, dropped, coalesced, and failed counts plus the last failure class when reported. The `_studio-history` destination exposes the bounded asynchronous SQLite history writer through the same panel; its counts describe buffered telemetry delivery rather than durable guarantees for every report.

The event subscription stays open after a run outcome because an interrupt-ignoring call can report its actual termination later. Reopening a completed run also subscribes for outstanding confirmations. Switching runs or leaving the page aborts the subscription; interrupted connections still reconnect with a cursor.

The selected-stage inspector also shows reported input/output summaries: data types and safe counts such as bytes, characters, chunks, or vectors. These describe the handoff without displaying document or query contents. Missing summaries are labeled **Not reported**.

On a generated web host, the same page discovers the available application workflows and shows **Start ingestion** / **Run query** plus execution history. Ingestion takes a path relative to the host's configured input folder; query takes question text. The backend enforces file boundaries. Switching workflow clears the input, and starting requires a separate explicit action. Launch errors preserve the current monitor and keep the input available for correction. Studio opens this shared page as an execution viewer; its existing Run drawer supplies Studio inputs.

### Reusable templates

The published template ID and settings are saved under the definition's `capabilities.template`. The monitor can switch its view for the current session without changing the published definition. Import a template ZIP through **Monitoring → Import a reusable template**. A manifest declares `schemaVersion: 1`, `id`, `name`, `version: "1"`, `html`, `css`, optional `renderer`, `executable`, and an `assets` list. Paths must describe bundled local assets. Imported IDs are pinned by the backend; use the returned catalog ID rather than guessing a URL.

Executable template code requires the explicit trust checkbox on import. The monitor embeds templates in an iframe with `sandbox="allow-scripts"` and without `allow-same-origin`. For declarative templates, the backend rejects custom script and injects only the bundled `template-bridge.js`. Backend content policy denies direct API/network access. The canonical timeline remains available outside the template, including when a pinned template is missing on the current host.

Declarative HTML can contain `[data-pipeline-status]`, `[data-pipeline-title]`, `[data-pipeline-nodes]`, `[data-pipeline-events]`, and `[data-pipeline-node="node-id"]` placeholders. The bridge fills these with safe text and progress elements from canonical snapshots; no custom JavaScript or trust checkbox is needed. A matching node placeholder includes its state, progress, and current attempt. Event lists retain at most the latest 100 entries inside the template. Built-in monitor settings support `title` and a valid CSS color in `accent`; settings contain simple string, number, or boolean values.

The parent sends the iframe these messages using `postMessage`:

```javascript
{type: 'plugintell.snapshot', schemaVersion: 1, data: snapshot}
{type: 'plugintell.event', schemaVersion: 1, data: executionEvent}
```

The opaque sandbox requires `targetOrigin: '*'`; only the selected renderer's window receives messages. No credentials are included. Renderer code should validate the message type/version and treat plugin content as text. The built-in page also exposes `window.PlugIntellMonitor.receiveSnapshot(snapshot)` and `.receiveEvent(event)` for hosts that supply execution data directly. Standard and Water share the same reducer and event contract.

Custom executable renderers must honor event visibility: omit `visible: false` events from their timeline, skip hidden `PLUGIN_MESSAGE` / `PROGRESS` domain updates, and retain lifecycle state changes. Each accepted event is followed by a projected canonical snapshot; declarative templates use that snapshot through the bundled bridge.

## Verification

```powershell
node --check pipeline-builder-ui/studio.js
node --check pipeline-builder-ui/project-build.js
node --check pipeline-builder-ui/monitor.js
node --check pipeline-builder-ui/template-bridge.js
node --test pipeline-builder-ui/studio.test.cjs pipeline-builder-ui/monitor.test.cjs pipeline-builder-ui/monitor-browser.test.cjs pipeline-builder-ui/template-bridge.test.cjs
```

The browser-independent tests exercise the actual application scripts with a small DOM harness. They cover state/events, retry and cancellation, progress, sequence deduplication, SSE framing, authenticated streaming, custom-template sandbox permissions, and observer scopes. They do not replace visual browser verification. The backend Maven package build bundles the Studio, build, and monitor HTML/CSS/JavaScript assets; test helpers and the icon notice are not application resources.

For manual browser QA, check wide and narrow layouts in both themes, card dragging and port alignment, inspector selection and resets, modal keyboard focus, published-version run preparation, uploads, traces/cancellation, and the separate build tab. See [local development](local-development.md) for starting the backend.
