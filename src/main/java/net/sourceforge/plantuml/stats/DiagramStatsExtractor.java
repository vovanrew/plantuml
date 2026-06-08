package net.sourceforge.plantuml.stats;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.atmp.CucaDiagram;
import net.sourceforge.plantuml.BlockUml;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.abel.Entity;
import net.sourceforge.plantuml.abel.GroupType;
import net.sourceforge.plantuml.abel.LeafType;
import net.sourceforge.plantuml.abel.Link;
import net.sourceforge.plantuml.activitydiagram3.ActivityDiagram3;
import net.sourceforge.plantuml.activitydiagram3.Branch;
import net.sourceforge.plantuml.activitydiagram3.Instruction;
import net.sourceforge.plantuml.activitydiagram3.InstructionFork;
import net.sourceforge.plantuml.activitydiagram3.InstructionGroup;
import net.sourceforge.plantuml.activitydiagram3.InstructionIf;
import net.sourceforge.plantuml.activitydiagram3.InstructionList;
import net.sourceforge.plantuml.activitydiagram3.InstructionPartition;
import net.sourceforge.plantuml.activitydiagram3.InstructionRepeat;
import net.sourceforge.plantuml.activitydiagram3.InstructionSplit;
import net.sourceforge.plantuml.activitydiagram3.InstructionSwitch;
import net.sourceforge.plantuml.activitydiagram3.InstructionWhile;
import net.sourceforge.plantuml.core.Diagram;
import net.sourceforge.plantuml.decoration.LinkDecor;
import net.sourceforge.plantuml.decoration.LinkType;
import net.sourceforge.plantuml.error.PSystemError;
import net.sourceforge.plantuml.klimt.creole.Display;
import net.sourceforge.plantuml.sequencediagram.Event;
import net.sourceforge.plantuml.sequencediagram.Message;
import net.sourceforge.plantuml.sequencediagram.MessageExo;
import net.sourceforge.plantuml.sequencediagram.Participant;
import net.sourceforge.plantuml.sequencediagram.SequenceDiagram;
import net.sourceforge.plantuml.skin.UmlDiagramType;

public class DiagramStatsExtractor {

	public static void main(String[] args) {
		if (args.length < 1) {
			System.err.println("Usage: DiagramStatsExtractor <file.puml> [file2.puml ...]");
			System.err.println("       DiagramStatsExtractor --dir <directory>");
			System.exit(1);
		}

		if ("--dir".equals(args[0]) && args.length >= 2) {
			final File dir = new File(args[1]);
			if (dir.isDirectory() == false) {
				System.err.println("Not a directory: " + args[1]);
				System.exit(1);
			}
			final File[] files = dir.listFiles((d, name) ->
				name.endsWith(".puml") || name.endsWith(".plantuml") || name.endsWith(".pu") || name.endsWith(".wsd") || name.endsWith(".uml") || name.endsWith(".iuml")
			);
			if (files != null) {
				for (final File f : files) {
					processFile(f.getAbsolutePath());
				}
			}
		} else {
			for (final String path : args) {
				processFile(path);
			}
		}
	}

	private static void processFile(String path) {
		try {
			final String source = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
			final SourceStringReader reader = new SourceStringReader(source);

			int blockIndex = 0;
			for (final BlockUml block : reader.getBlocks()) {
				final Diagram diagram = block.getDiagram();
				final String fileId = new File(path).getName();
				final String id = blockIndex == 0 ? fileId : fileId + "_" + blockIndex;

				if (diagram instanceof PSystemError) {
					printError(id, "parse_error");
				} else if (diagram instanceof CucaDiagram) {
					extractCucaDiagram(id, (CucaDiagram) diagram);
				} else if (diagram instanceof SequenceDiagram) {
					extractSequenceDiagram(id, (SequenceDiagram) diagram);
				} else if (diagram instanceof ActivityDiagram3) {
					extractActivityDiagram3(id, (ActivityDiagram3) diagram);
				} else {
					final String typeName = diagram.getClass().getSimpleName();
					printError(id, "unsupported_type:" + typeName);
				}
				blockIndex++;
			}
			// A truncated model output (e.g. missing @enduml) parses to zero
			// blocks; still emit one line so every input file is accounted for.
			if (blockIndex == 0) {
				printError(new File(path).getName(), "no_block");
			}
		} catch (IOException e) {
			final String fileId = new File(path).getName();
			printError(fileId, "io_error:" + e.getMessage());
		} catch (Exception e) {
			final String fileId = new File(path).getName();
			printError(fileId, "error:" + e.getMessage());
		}
	}

	// ---- CucaDiagram: class, component, state, object, usecase, deployment ----

	private static void extractCucaDiagram(String id, CucaDiagram diagram) {
		final Map<String, Integer> elements = new LinkedHashMap<>();
		final Map<String, Integer> connections = new LinkedHashMap<>();
		final String diagramType = getDiagramTypeName(diagram);

		// Count leaf entities
		for (final Entity entity : diagram.leafs()) {
			final LeafType leafType = entity.getLeafType();
			if (leafType == null)
				continue;
			final String key = leafType.name().toLowerCase();
			elements.merge(key, 1, Integer::sum);
		}

		// Count groups (packages, namespaces, etc.)
		for (final Entity group : diagram.groups()) {
			final GroupType groupType = group.getGroupType();
			if (groupType == null || groupType == GroupType.ROOT)
				continue;
			final String key = groupType.name().toLowerCase();
			elements.merge(key, 1, Integer::sum);
		}

		// Count links by decoration type
		int totalConnections = 0;
		for (final Link link : diagram.getLinks()) {
			totalConnections++;
			final LinkDecor decor1 = link.getType().getDecor1();
			final LinkDecor decor2 = link.getType().getDecor2();
			final String decorKey = classifyLinkDecor(decor1, decor2);
			connections.merge(decorKey, 1, Integer::sum);
		}

		// Named graph: leaf entities as nodes (groups are containers, excluded);
		// links as typed edges with display-name endpoints.
		final StringBuilder nodes = new StringBuilder("[");
		boolean firstNode = true;
		for (final Entity entity : diagram.leafs()) {
			final LeafType leafType = entity.getLeafType();
			if (leafType == null)
				continue;
			if (firstNode == false)
				nodes.append(",");
			nodes.append("{\"name\":").append(jsonString(entityName(entity)))
				.append(",\"type\":").append(jsonString(leafType.name().toLowerCase())).append("}");
			firstNode = false;
		}
		nodes.append("]");

		final StringBuilder edges = new StringBuilder("[");
		boolean firstEdge = true;
		for (final Link link : diagram.getLinks()) {
			if (firstEdge == false)
				edges.append(",");
			edges.append("{\"source\":").append(jsonString(entityName(link.getEntity1())))
				.append(",\"target\":").append(jsonString(entityName(link.getEntity2())))
				.append(",\"relation\":").append(jsonString(canonicalRelation(link.getType())))
				.append(",\"label\":").append(jsonString(flattenDisplay(link.getLabel()))).append("}");
			firstEdge = false;
		}
		edges.append("]");

		printJson(id, diagramType, elements, totalConnections, connections,
			nodes.toString(), edges.toString(), null);
	}

	// ---- SequenceDiagram ----

	private static void extractSequenceDiagram(String id, SequenceDiagram diagram) {
		final Map<String, Integer> elements = new LinkedHashMap<>();
		final Map<String, Integer> connections = new LinkedHashMap<>();

		// Count participants by type
		for (final Participant p : diagram.participants()) {
			final String key = p.getType().name().toLowerCase();
			elements.merge(key, 1, Integer::sum);
		}

		// Count messages
		int totalConnections = 0;
		for (final Event event : diagram.events()) {
			if (event instanceof Message) {
				totalConnections++;
				connections.merge("message", 1, Integer::sum);
			} else if (event instanceof MessageExo) {
				totalConnections++;
				connections.merge("message_exo", 1, Integer::sum);
			}
		}

		// Named graph: participants as nodes; messages as edges (relation
		// "message"). An exo message has only one real participant: the external
		// side is emitted as an empty endpoint.
		final StringBuilder nodes = new StringBuilder("[");
		boolean firstNode = true;
		for (final Participant p : diagram.participants()) {
			if (firstNode == false)
				nodes.append(",");
			nodes.append("{\"name\":").append(jsonString(participantName(p)))
				.append(",\"type\":").append(jsonString(p.getType().name().toLowerCase())).append("}");
			firstNode = false;
		}
		nodes.append("]");

		final StringBuilder edges = new StringBuilder("[");
		boolean firstEdge = true;
		for (final Event event : diagram.events()) {
			String source = null;
			String target = null;
			String label = "";
			if (event instanceof Message) {
				final Message m = (Message) event;
				source = participantName(m.getParticipant1());
				target = participantName(m.getParticipant2());
				label = flattenDisplay(m.getLabel());
			} else if (event instanceof MessageExo) {
				final MessageExo x = (MessageExo) event;
				source = participantName(x.getParticipant1());
				target = "";
				label = flattenDisplay(x.getLabel());
			} else {
				continue;
			}
			if (firstEdge == false)
				edges.append(",");
			edges.append("{\"source\":").append(jsonString(source))
				.append(",\"target\":").append(jsonString(target))
				.append(",\"relation\":\"message\"")
				.append(",\"label\":").append(jsonString(label)).append("}");
			firstEdge = false;
		}
		edges.append("]");

		printJson(id, "sequence", elements, totalConnections, connections,
			nodes.toString(), edges.toString(), null);
	}

	// ---- ActivityDiagram3 (beta syntax) ----

	private static void extractActivityDiagram3(String id, ActivityDiagram3 diagram) {
		final Map<String, Integer> elements = new LinkedHashMap<>();
		final Map<String, Integer> connections = new LinkedHashMap<>();

		final Instruction root = diagram.getRootInstruction();
		final int[] counts = new int[1]; // total connections
		countActivityConnections(root, connections, counts);

		// Activity diagrams have limited structural elements
		// Count instruction types as element info
		countActivityElements(root, elements);

		printJson(id, "activity", elements, counts[0], connections, "[]", "[]", null);
	}

	private static void countActivityConnections(Instruction instruction, Map<String, Integer> connections, int[] total) {
		if (instruction instanceof InstructionList) {
			final InstructionList list = (InstructionList) instruction;
			final List<Instruction> children = list.getAll();
			// Sequential connections between consecutive instructions
			if (children.size() > 1) {
				final int seqCount = children.size() - 1;
				connections.merge("sequential", seqCount, Integer::sum);
				total[0] += seqCount;
			}
			for (final Instruction child : children) {
				countActivityConnections(child, connections, total);
			}
		} else if (instruction instanceof InstructionIf) {
			final InstructionIf ifInst = (InstructionIf) instruction;
			final List<Branch> thens = ifInst.getThens();
			// One connection per branch from the decision node
			final int branchCount = thens.size();
			connections.merge("branch", branchCount, Integer::sum);
			total[0] += branchCount;
			for (final Branch branch : thens) {
				countActivityConnections(branch.getInstructionList(), connections, total);
			}
			final Branch elseBranch = ifInst.getElseBranch();
			if (elseBranch != null) {
				connections.merge("branch", 1, Integer::sum);
				total[0] += 1;
				countActivityConnections(elseBranch.getInstructionList(), connections, total);
			}
			// Merge point after if/else
			connections.merge("merge", 1, Integer::sum);
			total[0] += 1;
		} else if (instruction instanceof InstructionGroup) {
			final InstructionGroup groupInst = (InstructionGroup) instruction;
			countActivityConnections(groupInst.getInstructionList(), connections, total);
		} else if (instruction instanceof InstructionPartition) {
			final InstructionPartition partInst = (InstructionPartition) instruction;
			countActivityConnections(partInst.getInstructionList(), connections, total);
		} else if (instruction instanceof InstructionWhile) {
			final InstructionWhile whileInst = (InstructionWhile) instruction;
			// Entry into loop body + loop-back + exit
			connections.merge("loop_entry", 1, Integer::sum);
			connections.merge("loop_back", 1, Integer::sum);
			connections.merge("loop_exit", 1, Integer::sum);
			total[0] += 3;
			countActivityConnections(whileInst.getRepeatList(), connections, total);
		} else if (instruction instanceof InstructionRepeat) {
			final InstructionRepeat repeatInst = (InstructionRepeat) instruction;
			// Entry + repeat-back + exit
			connections.merge("loop_entry", 1, Integer::sum);
			connections.merge("loop_back", 1, Integer::sum);
			connections.merge("loop_exit", 1, Integer::sum);
			total[0] += 3;
			countActivityConnections(repeatInst.getRepeatList(), connections, total);
		} else if (instruction instanceof InstructionFork) {
			final InstructionFork forkInst = (InstructionFork) instruction;
			final List<InstructionList> forks = forkInst.getForks();
			final int forkCount = forks.size();
			// Split into N branches + join from N branches
			connections.merge("fork_split", forkCount, Integer::sum);
			connections.merge("fork_join", forkCount, Integer::sum);
			total[0] += forkCount * 2;
			for (final InstructionList fork : forks) {
				countActivityConnections(fork, connections, total);
			}
		} else if (instruction instanceof InstructionSplit) {
			final InstructionSplit splitInst = (InstructionSplit) instruction;
			final List<InstructionList> splits = splitInst.getSplits();
			final int splitCount = splits.size();
			connections.merge("fork_split", splitCount, Integer::sum);
			connections.merge("fork_join", splitCount, Integer::sum);
			total[0] += splitCount * 2;
			for (final InstructionList split : splits) {
				countActivityConnections(split, connections, total);
			}
		} else if (instruction instanceof InstructionSwitch) {
			final InstructionSwitch switchInst = (InstructionSwitch) instruction;
			final List<Branch> cases = switchInst.getSwitches();
			final int caseCount = cases.size();
			connections.merge("branch", caseCount, Integer::sum);
			total[0] += caseCount;
			for (final Branch branch : cases) {
				countActivityConnections(branch.getInstructionList(), connections, total);
			}
			// Merge point after switch
			connections.merge("merge", 1, Integer::sum);
			total[0] += 1;
		}
		// InstructionSimple, InstructionStart, InstructionStop, InstructionEnd, etc.
		// are leaf nodes with no outgoing connections of their own
	}

	private static void countActivityElements(Instruction instruction, Map<String, Integer> elements) {
		if (instruction instanceof InstructionList) {
			final InstructionList list = (InstructionList) instruction;
			for (final Instruction child : list.getAll()) {
				countActivityElements(child, elements);
			}
		} else if (instruction instanceof InstructionGroup) {
			final InstructionGroup groupInst = (InstructionGroup) instruction;
			elements.merge("group", 1, Integer::sum);
			countActivityElements(groupInst.getInstructionList(), elements);
		} else if (instruction instanceof InstructionPartition) {
			final InstructionPartition partInst = (InstructionPartition) instruction;
			elements.merge("partition", 1, Integer::sum);
			countActivityElements(partInst.getInstructionList(), elements);
		} else if (instruction instanceof InstructionIf) {
			final InstructionIf ifInst = (InstructionIf) instruction;
			elements.merge("decision", 1, Integer::sum);
			for (final Branch branch : ifInst.getThens()) {
				countActivityElements(branch.getInstructionList(), elements);
			}
			final Branch elseBranch = ifInst.getElseBranch();
			if (elseBranch != null)
				countActivityElements(elseBranch.getInstructionList(), elements);
		} else if (instruction instanceof InstructionWhile) {
			final InstructionWhile whileInst = (InstructionWhile) instruction;
			elements.merge("loop", 1, Integer::sum);
			countActivityElements(whileInst.getRepeatList(), elements);
			final Instruction specialOut = whileInst.getSpecialOut();
			if (specialOut != null) {
				countActivityElements(specialOut, elements);
			}
		} else if (instruction instanceof InstructionRepeat) {
			final InstructionRepeat repeatInst = (InstructionRepeat) instruction;
			elements.merge("loop", 1, Integer::sum);
			countActivityElements(repeatInst.getRepeatList(), elements);
		} else if (instruction instanceof InstructionFork) {
			final InstructionFork forkInst = (InstructionFork) instruction;
			elements.merge("fork", 1, Integer::sum);
			for (final InstructionList fork : forkInst.getForks()) {
				countActivityElements(fork, elements);
			}
		} else if (instruction instanceof InstructionSplit) {
			final InstructionSplit splitInst = (InstructionSplit) instruction;
			elements.merge("split", 1, Integer::sum);
			for (final InstructionList split : splitInst.getSplits()) {
				countActivityElements(split, elements);
			}
		} else if (instruction instanceof InstructionSwitch) {
			final InstructionSwitch switchInst = (InstructionSwitch) instruction;
			elements.merge("switch", 1, Integer::sum);
			for (final Branch branch : switchInst.getSwitches()) {
				countActivityElements(branch.getInstructionList(), elements);
			}
		} else {
			// Leaf instruction (action, start, stop, end, etc.)
			final String name = instruction.getClass().getSimpleName()
				.replace("Instruction", "").toLowerCase();
			elements.merge(name, 1, Integer::sum);
		}
	}

	// ---- Helpers ----

	// Flatten a Display (visible label) to a single plain-text string.
	private static String flattenDisplay(Display display) {
		if (display == null)
			return "";
		final StringBuilder sb = new StringBuilder();
		for (final CharSequence cs : display.asList()) {
			if (cs == null)
				continue;
			if (sb.length() > 0)
				sb.append(" ");
			sb.append(cs.toString());
		}
		return sb.toString().trim();
	}

	// Node identity is the visible display name; fall back to the code/alias.
	private static String entityName(Entity entity) {
		if (entity == null)
			return "";
		String s = flattenDisplay(entity.getDisplay());
		if (s.isEmpty())
			s = entity.getName();
		return s == null ? "" : s;
	}

	private static String participantName(Participant p) {
		if (p == null)
			return "";
		String s = flattenDisplay(p.getDisplay(false));
		if (s.isEmpty())
			s = p.getCode();
		return s == null ? "" : s;
	}

	private static boolean isInheritanceDecor(LinkDecor d) {
		return d == LinkDecor.EXTENDS || d == LinkDecor.REDEFINES || d == LinkDecor.DEFINEDBY;
	}

	// Map a PlantUML link to one canonical UML relation category. Realization
	// (dashed triangle) folds into inheritance; a dashed arrow is a dependency,
	// any other plain line is an association.
	private static String canonicalRelation(LinkType type) {
		final LinkDecor d1 = type.getDecor1();
		final LinkDecor d2 = type.getDecor2();
		if (isInheritanceDecor(d1) || isInheritanceDecor(d2))
			return "inheritance";
		if (d1 == LinkDecor.COMPOSITION || d2 == LinkDecor.COMPOSITION)
			return "composition";
		if (d1 == LinkDecor.AGREGATION || d2 == LinkDecor.AGREGATION)
			return "aggregation";
		final String style = type.getStyle().toString();
		final boolean dashed = style.startsWith("DASHED") || style.startsWith("DOTTED");
		return dashed ? "dependency" : "association";
	}

	private static String classifyLinkDecor(LinkDecor decor1, LinkDecor decor2) {
		// Pick the more meaningful decoration (non-NONE)
		if (decor1 == LinkDecor.EXTENDS || decor2 == LinkDecor.EXTENDS)
			return "extends";
		if (decor1 == LinkDecor.COMPOSITION || decor2 == LinkDecor.COMPOSITION)
			return "composition";
		if (decor1 == LinkDecor.AGREGATION || decor2 == LinkDecor.AGREGATION)
			return "aggregation";
		if (decor1 == LinkDecor.REDEFINES || decor2 == LinkDecor.REDEFINES)
			return "redefines";
		if (decor1 == LinkDecor.DEFINEDBY || decor2 == LinkDecor.DEFINEDBY)
			return "definedby";
		if (decor1 == LinkDecor.ARROW || decor2 == LinkDecor.ARROW
			|| decor1 == LinkDecor.ARROW_TRIANGLE || decor2 == LinkDecor.ARROW_TRIANGLE)
			return "arrow";
		if (decor1 == LinkDecor.NONE && decor2 == LinkDecor.NONE)
			return "none";
		// Fallback: use the name of whichever is not NONE
		if (decor1 != LinkDecor.NONE)
			return decor1.name().toLowerCase();
		return decor2.name().toLowerCase();
	}

	private static String getDiagramTypeName(CucaDiagram diagram) {
		final UmlDiagramType type = diagram.getUmlDiagramType();
		if (type == null)
			return "unknown";
		if (type == UmlDiagramType.DESCRIPTION)
			return "component";
		return type.name().toLowerCase();
	}

	private static void printJson(String id, String diagramType, Map<String, Integer> elements,
			int totalConnections, Map<String, Integer> connections, String nodesJson, String edgesJson,
			String note) {
		final StringBuilder sb = new StringBuilder();
		sb.append("{");
		sb.append("\"file\":").append(jsonString(id));
		sb.append(",\"diagram_type\":").append(jsonString(diagramType));
		sb.append(",\"elements\":").append(mapToJson(elements));
		sb.append(",\"elements_total\":").append(sumValues(elements));
		sb.append(",\"connections\":").append(mapToJson(connections));
		sb.append(",\"connections_total\":").append(totalConnections);
		sb.append(",\"nodes\":").append(nodesJson);
		sb.append(",\"edges\":").append(edgesJson);
		if (note != null)
			sb.append(",\"note\":").append(jsonString(note));
		sb.append(",\"error\":null");
		sb.append("}");
		System.out.println(sb.toString());
	}

	private static void printError(String id, String error) {
		final StringBuilder sb = new StringBuilder();
		sb.append("{");
		sb.append("\"file\":").append(jsonString(id));
		sb.append(",\"diagram_type\":null");
		sb.append(",\"elements\":{}");
		sb.append(",\"elements_total\":0");
		sb.append(",\"connections\":{}");
		sb.append(",\"connections_total\":0");
		sb.append(",\"nodes\":[]");
		sb.append(",\"edges\":[]");
		sb.append(",\"error\":").append(jsonString(error));
		sb.append("}");
		System.out.println(sb.toString());
	}

	private static String jsonString(String s) {
		if (s == null)
			return "null";
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
			.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
	}

	private static String mapToJson(Map<String, Integer> map) {
		final StringBuilder sb = new StringBuilder();
		sb.append("{");
		boolean first = true;
		for (final Map.Entry<String, Integer> entry : map.entrySet()) {
			if (first == false)
				sb.append(",");
			sb.append(jsonString(entry.getKey())).append(":").append(entry.getValue());
			first = false;
		}
		sb.append("}");
		return sb.toString();
	}

	private static int sumValues(Map<String, Integer> map) {
		int sum = 0;
		for (final int v : map.values()) {
			sum += v;
		}
		return sum;
	}
}
