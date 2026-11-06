# Studio workbench

Studio is the existing static HTML/CSS/JavaScript application served by Micronaut. The workbench uses Fluent-inspired controls and original local outline icons, with restrained surfaces and typography. It introduces no frontend framework, remote icon service, or external font dependency.

## Workspace

The command bar groups **Save draft**, **Publish**, and **Build app**. Project name, saved-project selection, import/export, connection management, and chunk preview are directly below it. **Project overview** keeps catalog/execution metrics available without taking space from the canvas.

On wide screens, the catalog appears on the left, ingestion above query in the center, and the selected-node inspector on the right. **Catalog** and **Inspector** toggle their panels. On smaller screens, the panels open over the workspace. **View → Focus canvas** gives the workflows the full width. Escape closes an overlay panel or clears a selected output port.

Appearance follows the system and control spacing defaults to Comfortable. **View** also offers light/dark overrides and compact spacing. These preferences and node positions are stored locally; pipeline settings and secrets are not stored in local storage.

## Add, arrange, and connect

Search and the **All / Ingestion / Query** segments filter the plugin catalog together. Filtering does not hide either workflow. Drag a compatible plugin into a lane, or use its labeled Add action.

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

## Verification

```powershell
node --check pipeline-builder-ui/studio.js
node --check pipeline-builder-ui/project-build.js
node --test pipeline-builder-ui/studio.test.cjs
```

The browser-independent tests exercise the actual application scripts with a small DOM harness. They cover state/events and do not replace visual browser verification. The backend Maven package build bundles the existing five frontend assets; the test file and icon notice are not application resources.

For manual browser QA, check wide and narrow layouts in both themes, card dragging and port alignment, inspector selection and resets, modal keyboard focus, published-version run preparation, uploads, traces/cancellation, and the separate build tab. See [local development](local-development.md) for starting the backend.
