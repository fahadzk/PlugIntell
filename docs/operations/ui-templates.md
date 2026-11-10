# Local UI templates

Local UI consumes execution events and maintains the monitor's state. A **template** renders that state; it is separate from processing plugins, data edges, adapter subscriptions, and the application host. Configure one shared view under `capabilities.template`. Multiple Local UI instances retain their own node/workflow subscriptions and filters while sharing that view.

## Choose and configure a view in Studio

1. Open **UI templates**, beside **Processing** and **Adapters** in the catalog.
2. Choose **Preview** on Aurora Studio, Midnight Observatory, or an imported template. The preview uses clearly marked sample data and does not execute a workflow. Change its settings to see the preview update.
3. Choose **Use with Local UI**. Studio reuses an existing Local UI adapter. If none exists, it adds one and subscribes it to each populated workflow. Existing adapter scopes and filters are preserved.
4. In **Local UI template**, configure the form fields. Advanced JSON remains available. The Local UI adapter inspector also has **Choose UI template**.
5. Save the draft and publish. The selected content pin and settings belong to that immutable published version. Run a workflow and open **Live monitor**.

Studio uses its existing server. For an exported application, explicitly choose the **Micronaut web host**, then save and publish before generating. Templates do not silently add a server. Run the generated web application with `run.bat serve` / `sh run.sh serve`. Native projects use the executable directly through those scripts. CLI-only projects do not bundle monitor assets.

| External bundle | Design | Configurable view |
| --- | --- | --- |
| [Aurora Studio](../../pipeline-builder-ui/templates/aurora.zip) | Light glass surfaces, violet accent, stage-card workspace | Title, subtitle, accent, cards/list layout, compact spacing, timeline/health visibility, timeline limit |
| [Midnight Observatory](../../pipeline-builder-ui/templates/midnight.zip) | Dark command center, teal accent, processing stations and terminal-style log | The same controls, with its own visual defaults |

Both include actual stage counts, per-attempt progress when reported, measured durations, a keyboard-accessible stage inspector, processing connections, adapter diagnostics and an event timeline. Unknown totals stay unknown. Retry state and cancellation requests remain explicit. Animation indicates a running station; there are no invented throughput values, queues or transfers. Both honor reduced-motion preferences and work without remote fonts, scripts or images.

## Bring your own external HTML/CSS template

Use **Download external ZIP** to start from either theme, or create a ZIP containing these files at its root:

```text
manifest.json
index.html
styles.css
assets/                 optional local images or woff2 fonts
```

Do not ZIP an enclosing directory. In **Import a reusable template**, choose the ZIP and click **Import template**. Leave executable trust unchecked for a declarative HTML/CSS template. Studio validates the archive, returns a content-pinned ID, adds it to **UI templates**, and selects it for Local UI. Review its settings, save and publish.

Here is a minimal manifest with typed settings:

```json
{
  "schemaVersion": 1,
  "id": "my-monitor",
  "name": "My monitor",
  "description": "Our team's execution view",
  "version": "1",
  "html": "index.html",
  "css": "styles.css",
  "executable": false,
  "assets": [],
  "settingsSchema": {
    "type": "object",
    "additionalProperties": false,
    "properties": {
      "title": {"type": "string", "title": "Display title", "maxLength": 96, "default": ""},
      "accent": {"type": "string", "title": "Accent color", "format": "color", "default": "#336699"},
      "showTimeline": {"type": "boolean", "title": "Show events", "default": true},
      "timelineLimit": {"type": "integer", "minimum": 5, "maximum": 100, "default": 30}
    }
  }
}
```

The supported schema subset is `type: object`, `properties`, `required`, and boolean `additionalProperties`. Each field supports `string`, `boolean`, `integer` or `number`, plus `title`, `description`, `default`, `enum`, `maxLength`, `minimum`, `maximum`, and string `format: color`. Color values use six-digit hex notation. This is a deliberately bounded subset, not a complete JSON Schema implementation. There are at most 32 settings, 2,048 characters per string, and finite numbers only. Imported schemas, defaults, draft validation, publication and generation are checked by the backend. Legacy templates without a schema keep their advanced simple-value settings.

Start `index.html` with declarative hooks:

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>My monitor</title>
</head>
<body>
  <h1 data-pipeline-title>Workflow</h1>
  <p data-pipeline-status role="status">Waiting</p>
  <div data-pipeline-nodes></div>
  <aside data-pipeline-inspector></aside>
  <section data-pipeline-events-panel><ol data-pipeline-events></ol></section>
</body>
</html>
```

The two exact inert metadata forms above are allowed; refresh/policy metadata is rejected. The host adds `styles.css` and its trusted `/ui/template-bridge.js`. A declarative bundle must not include its own JavaScript or event-handler attributes.

The bridge sets `--accent`, `data-template-density` (`compact` / `comfortable`) and `data-template-layout` (`cards` / `list`) on the document root. In CSS, style `.template-stage`, its `data-status` attribute, and `[aria-pressed=true]`. Stage elements remain sections, with keyboard button semantics for selection. Additional settings can be rendered as plain text through `data-template-setting="fieldName"`.

| Hook | Supplied data |
| --- | --- |
| `data-pipeline-title`, `data-pipeline-status` | Configured title/pipeline name and authoritative run state |
| `data-pipeline-nodes` | Descriptor-defined stages, status, real progress, attempt and measured duration |
| `data-pipeline-node="node-id"` | One fixed stage slot |
| `data-pipeline-inspector` | Selected stage and safe input/output type/count summaries |
| `data-pipeline-connections` | The descriptor's actual processing connections |
| `data-pipeline-health` | Reported adapter status, queued/delivered/dropped/coalesced/failed counts |
| `data-pipeline-events` | Visible projected events, capped at 100 rows |
| `data-pipeline-count="total"` | Number of descriptor stages; also supports `succeeded`, `active`, `failed` |
| `data-pipeline-run`, `data-pipeline-sequence`, `data-pipeline-workflow` | Run identity, cursor and workflow label |
| `data-pipeline-events-panel`, `data-pipeline-health-panel` | Containers controlled by `showTimeline` / `showHealth` |

Actual query answers, citations and execution controls stay in the trusted generated host page, outside the template frame. Templates receive execution summaries, not document text, prompts, credentials or answer content. They cannot control filesystem/provider settings.

## Files, pins and generation

The readable starter sources are in [Aurora](../../pipeline-builder-ui/templates/aurora/index.html) and [Midnight](../../pipeline-builder-ui/templates/midnight/index.html). After editing a bundled source, rebuild and verify the distributable ZIPs:

```shell
python tools/package_ui_templates.py
python tools/package_ui_templates.py --check
```

For your own source directory, create a ZIP without a parent folder:

```shell
python -m zipfile -c my-monitor.zip manifest.json index.html styles.css
```

Run that command from the directory containing those three files; add `assets` when needed. Importing yields an ID such as `my-monitor-0123456789abcdef`; use the **actual returned ID**, not this example. The suffix identifies the original ZIP content. Editing and reimporting creates a different pin. Selected bundled versions are preserved in the backend's template store so an application upgrade does not silently change a published view. Back up that store with SQLite; do not delete archives referenced by published pipelines.

`GET /api/v1/templates` lists descriptors, renderer URLs, settings schemas and downloadable bundle URLs. `GET /api/v1/templates/{id}/bundle` returns the original ZIP. Import uses multipart `POST /api/v1/templates/import`; authenticated imports require ADMIN/EDITOR. Downloads use normal authenticated API access. Renderer assets remain isolated by their sandbox/CSP.

The stored capability fragment has this shape:

```json
{
  "adapters": [{"id": "local-ui", "type": "ui", "version": "1", "config": {}}],
  "subscriptions": [{"adapterId": "local-ui", "workflow": "DOCUMENT_INGESTION", "nodeIds": [], "eventTypes": [], "severities": []}],
  "host": {"type": "micronaut-web", "port": 8081, "openBrowser": false},
  "template": {"id": "my-monitor-0123456789abcdef", "version": "1", "settings": {"title": "Research workflow", "accent": "#336699", "showTimeline": false, "timelineLimit": 20}}
}
```

An empty `nodeIds` subscribes the whole named workflow. Add another subscription for the query workflow when it exists. Keep view configuration in `template`; `ui` adapter `config` remains `{}`. **Test adapter** validates the selected template pin/settings when called from Studio; it is not a network connection to a browser.

Both generators bundle only the selected external view into `public/templates/<pin>/`. Generated applications serve it locally; they do not fetch a remote template URL. JAR and native web builds use the same renderer/state protocol. Changing a template means editing the draft, publishing and regenerating the application.

The Python generator accepts `--web --ui --template aurora` or `--web --ui --template midnight` alongside your existing plugin arguments. These friendly aliases resolve to exact content pins. For your own ZIP, use `--web --ui --template-bundle my-monitor.zip`. Supply view settings through `--capabilities settings.json`; when using a bundled theme, its alias can appear in that file and will resolve to the pinned ID. Explicit settings override declared defaults. See [GENERATOR.md](../../GENERATOR.md) for complete plugin arguments and JVM/native startup commands.

## Trusted executable renderers

JavaScript templates remain optional trusted code. Declare `executable: true`, `renderer: "renderer.js"`, include the local script in HTML, and acknowledge trust during import (`--trusted-template` for Python). Validate `event.source === parent`, `event.data.schemaVersion === 1`, and consume `plugintell.snapshot` as canonical state. `plugintell.event` is optional event detail; the parent also supplies a fresh snapshot after updates. Ordering, replay and deduplication remain in the host client.

The iframe allows scripts but has no same-origin access. CSP denies direct network/control-API access. Never render plugin strings with `innerHTML`, count replayed events as new work, or derive topology from whichever events happen to arrive. Use the descriptor, safe text nodes, reported progress and explicit lifecycle states. ZIP limits remain 2 MB compressed, 4 MB expanded, 100 files and 1 MB per resource; traversal, duplicates, undeclared executable files and unsupported resources are rejected.
