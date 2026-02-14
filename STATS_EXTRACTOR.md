# DiagramStatsExtractor

A tool for extracting structural element and connection statistics from PlantUML diagrams. It reuses PlantUML's own internal parser to achieve compiler-grade accuracy, including full support for activity diagrams with implicit control flow edges.

This fork adds minimal modifications to PlantUML (version 1.2025.9) to expose internal data structures required for statistics extraction. No changes were made to PlantUML's parsing logic or diagram rendering.

## Changes from Upstream PlantUML

- **10 public getter methods** added to activity diagram instruction classes to expose private fields for tree traversal (44 lines across 9 files)
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

The tool writes one JSON object per diagram to standard output (JSON Lines format):

```json
{
  "file": "example.puml",
  "diagram_type": "class",
  "elements": {"class": 5, "interface": 2, "package": 1},
  "elements_total": 8,
  "connections": {"extends": 3, "arrow": 4},
  "connections_total": 7,
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
| `error` | Null on success. Descriptive string on failure (e.g., `parse_error`, `unsupported_type:TimingDiagram`). |

### Element Type Keys

**Class/component/state/object/usecase/deployment diagrams**: derived from PlantUML's `LeafType` and `GroupType` enums (e.g., `class`, `interface`, `abstract_class`, `enum`, `component`, `state`, `package`).

**Sequence diagrams**: derived from `ParticipantType` enum (e.g., `participant`, `actor`, `database`, `boundary`, `control`).

**Activity diagrams**: derived from instruction node types (e.g., `simple`, `start`, `stop`, `decision`, `loop`, `fork`, `switch`).

### Connection Type Keys

**Class/component/state/object/usecase/deployment diagrams**: classified by link decoration (e.g., `extends`, `composition`, `aggregation`, `arrow`, `none`).

**Sequence diagrams**: `message` (between two participants) or `message_exo` (to/from external actor).

**Activity diagrams**: classified by control flow semantics (`sequential`, `branch`, `merge`, `loop_entry`, `loop_back`, `loop_exit`, `fork_split`, `fork_join`).

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