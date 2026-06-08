 Fork of [PlantUML](https://github.com/plantuml/plantuml) (v1.2025.9) with a custom statistics extraction tool.
# DiagramStatsExtractor

A tool for extracting structural statistics **and a named graph** from PlantUML diagrams. It reuses PlantUML's own internal parser to achieve compiler-grade accuracy, including full support for activity diagrams with implicit control flow edges.

For class and sequence diagrams the tool additionally emits a typed graph: named `nodes` (classes / participants) and typed `edges` (relationships / messages with endpoints), enabling name-matched comparison between two diagrams (e.g. a model prediction against a ground truth). The per-type count fields are retained unchanged.

This fork adds minimal modifications to PlantUML (version 1.2025.9) to expose internal data structures required for statistics extraction. No changes were made to PlantUML's parsing logic or diagram rendering.

## Changes from Upstream PlantUML

- **14 public getter methods** added across activity diagram classes to expose private fields for instruction tree traversal (56 lines across 12 files)
- **1 new class** added: `net.sourceforge.plantuml.stats.DiagramStatsExtractor` — the extraction tool entry point

## Prerequisites

- Java 8 or later

## Build

```bash
./gradlew build -x test -x javaDoc
```

This produces a single executable JAR at `build/libs/plantuml-1.2025.9.jar`.

## Usage

```bash
# Single file
java -cp build/libs/plantuml-1.2025.9.jar \
  net.sourceforge.plantuml.stats.DiagramStatsExtractor diagram.puml

# Multiple files
java -cp build/libs/plantuml-1.2025.9.jar \
  net.sourceforge.plantuml.stats.DiagramStatsExtractor file1.puml file2.puml file3.puml

# All PlantUML files in a directory
java -cp build/libs/plantuml-1.2025.9.jar \
  net.sourceforge.plantuml.stats.DiagramStatsExtractor --dir /path/to/puml/files/
```

The `--dir` option processes all files with extensions `.puml`, `.plantuml`, `.pu`, `.wsd`, `.uml`, and `.iuml`.

## Output Format

The tool writes one JSON object per diagram to standard output (JSON Lines format). A file that contains no parseable diagram (e.g. a truncated source missing `@enduml`) still yields one record, with `error: "no_block"`, so every input file is accounted for:

```json
{
  "file": "example.puml",
  "diagram_type": "class",
  "elements": {"class": 5, "interface": 2, "package": 1},
  "elements_total": 8,
  "connections": {"extends": 3, "arrow": 4},
  "connections_total": 7,
  "nodes": [
    {"name": "Order", "type": "class"},
    {"name": "Customer", "type": "class"}
  ],
  "edges": [
    {"source": "Order", "target": "Customer", "relation": "association", "label": "places"}
  ],
  "error": null
}
```

### Fields

| Field | Description |
|-------|-------------|
| `file` | Input filename. Multi-diagram files receive a numeric suffix (e.g., `file.puml_1`). |
| `diagram_type` | Detected diagram type: `class`, `sequence`, `activity`, `state`, `component`, `object`, etc. Null on parse failure. |
| `elements` | Element counts by type. Keys are dynamic and depend on diagram contents. |
| `elements_total` | Sum of all element counts. |
| `connections` | Connection counts by type. Keys are dynamic and depend on diagram type. |
| `connections_total` | Sum of all connection counts. |
| `nodes` | Named entities (class/sequence diagrams). Each is `{name, type}`. Empty for unsupported types and on error. See [Named Graph](#named-graph-nodes-and-edges). |
| `edges` | Typed relationships with endpoints (class/sequence diagrams). Each is `{source, target, relation, label}`. Empty for unsupported types and on error. |
| `error` | Null on success. Descriptive string on failure (e.g., `parse_error`, `no_block`, `unsupported_type:TimingDiagram`). |

### Element Type Keys

**Class/component/state/object/usecase/deployment diagrams**: derived from PlantUML's `LeafType` and `GroupType` enums (e.g., `class`, `interface`, `abstract_class`, `enum`, `component`, `state`, `package`).

**Sequence diagrams**: derived from `ParticipantType` enum (e.g., `participant`, `actor`, `database`, `boundary`, `control`).

**Activity diagrams**: derived from instruction node types (e.g., `simple`, `start`, `stop`, `decision`, `loop`, `fork`, `split`, `switch`, `group`, `partition`).

### Connection Type Keys

**Class/component/state/object/usecase/deployment diagrams**: classified by link decoration (e.g., `extends`, `composition`, `aggregation`, `redefines`, `definedby`, `arrow`, `none`).

**Sequence diagrams**: `message` (between two participants) or `message_exo` (to/from external actor).

**Activity diagrams**: classified by control flow semantics (`sequential`, `branch`, `merge`, `loop_entry`, `loop_back`, `loop_exit`, `fork_split`, `fork_join`).

## Named Graph (nodes and edges)

For **class** and **sequence** diagrams the tool emits a typed graph in addition to the counts above. (Other diagram types emit empty `nodes` and `edges`.)

**Nodes** are first-class entities — class-like leaves (`class`, `interface`, `enum`, `abstract`, ...) for class diagrams, participants (`participant`, `actor`, `boundary`, ...) for sequence diagrams. Grouping containers (packages, namespaces) are **not** nodes, though they remain in the `elements` counts. Each node is `{"name": ..., "type": ...}`.

**Node identity is the visible display name** — the label as rendered in the image — not a source-level alias. An entity declared `class "Order Service" as OS` is named `Order Service`. This makes the graph suitable for matching a diagram against another that uses different aliases for the same visible entities.

**Edges** are `{"source", "target", "relation", "label"}`, where `source`/`target` are node display names. The `relation` is canonicalized to a fixed vocabulary:

| `relation` | Source construct |
|---|---|
| `inheritance` | generalization or realization (triangle head; solid or dashed) |
| `composition` | filled-diamond decoration (`*--`) |
| `aggregation` | hollow-diamond decoration (`o--`) |
| `dependency` | dashed line carrying an arrow head (`..>`) |
| `association` | any other plain line (`--`, `-->`) |
| `message` | any sequence-diagram message |

A sequence message whose counterpart is outside the diagram (`message_exo`) is emitted with an empty external endpoint.

## Supported Diagram Types

| Diagram Type | Extraction Strategy | Coverage |
|-------------|-------------------|----------|
| class, object, component, deployment, usecase, state | Entity-Link (CucaDiagram) | Elements and connections |
| sequence | Participant-Event (SequenceDiagram) | Elements and connections |
| activity (beta syntax) | Instruction tree traversal (ActivityDiagram3) | Elements and connections |
| activity (legacy syntax) | Entity-Link (CucaDiagram) | Elements and connections |
| Other (timing, mindmap, etc.) | Not supported | Reported as `unsupported_type` error |

## Batch Processing

For large-scale processing, use the `--dir` option or pass multiple file paths. All files are processed within a single JVM process, avoiding per-file startup overhead.

```bash
# Process entire dataset, save results as JSONL
java -cp build/libs/plantuml-1.2025.9.jar \
  net.sourceforge.plantuml.stats.DiagramStatsExtractor --dir /path/to/dataset/ \
  > results.jsonl

# Errors are written to stderr, results to stdout
java -cp build/libs/plantuml-1.2025.9.jar \
  net.sourceforge.plantuml.stats.DiagramStatsExtractor --dir /path/to/dataset/ \
  > results.jsonl 2> errors.log
```

## License

This fork retains PlantUML's original GNU General Public License v3 (GPL-3.0).
