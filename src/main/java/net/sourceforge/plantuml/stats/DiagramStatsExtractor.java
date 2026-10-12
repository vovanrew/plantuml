package net.sourceforge.plantuml.stats;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import net.atmp.CucaDiagram;
import net.sourceforge.plantuml.BlockUml;
import net.sourceforge.plantuml.EmbeddedDiagram;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.StringUtils;
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
import net.sourceforge.plantuml.cucadiagram.BodyEnhancedAbstract;
import net.sourceforge.plantuml.decoration.LinkDecor;
import net.sourceforge.plantuml.decoration.LinkType;
import net.sourceforge.plantuml.error.PSystemError;
import net.sourceforge.plantuml.klimt.creole.CreoleHorizontalLine;
import net.sourceforge.plantuml.klimt.creole.CreoleMode;
import net.sourceforge.plantuml.klimt.creole.Display;
import net.sourceforge.plantuml.klimt.creole.Sheet;
import net.sourceforge.plantuml.klimt.creole.Stripe;
import net.sourceforge.plantuml.klimt.creole.atom.Atom;
import net.sourceforge.plantuml.klimt.creole.legacy.AtomText;
import net.sourceforge.plantuml.klimt.creole.legacy.CreoleParser;
import net.sourceforge.plantuml.klimt.font.FontConfiguration;
import net.sourceforge.plantuml.klimt.font.UFont;
import net.sourceforge.plantuml.klimt.geom.HorizontalAlignment;
import net.sourceforge.plantuml.sequencediagram.AbstractMessage;
import net.sourceforge.plantuml.sequencediagram.Event;
import net.sourceforge.plantuml.sequencediagram.Message;
import net.sourceforge.plantuml.sequencediagram.MessageExo;
import net.sourceforge.plantuml.sequencediagram.MessageExoType;
import net.sourceforge.plantuml.sequencediagram.Participant;
import net.sourceforge.plantuml.sequencediagram.SequenceDiagram;
import net.sourceforge.plantuml.skin.UmlDiagramType;
import net.sourceforge.plantuml.style.ISkinParam;
import net.sourceforge.plantuml.style.SName;
import net.sourceforge.plantuml.style.StyleSignatureBasic;

public class DiagramStatsExtractor {

	// How a node name is read from its label. "drawn" is the default: the text
	// PlantUML draws, largest-font-size line(s) only. "markup" keeps every drawn
	// line. "raw" joins the label lines as typed.
	private static final String NAMES_RAW = "raw";
	private static final String NAMES_MARKUP = "markup";
	private static final String NAMES_DRAWN = "drawn";
	private static String namesMode = NAMES_DRAWN;

	// Emit, per association edge, which end carries an arrowhead.
	private static boolean emitArrowheads = true;

	// Emit every link and every message as an edge, whether PlantUML draws it
	// or not. Off by default: an edge is a link or a message PlantUML draws.
	private static boolean emitUndrawnEdges = false;

	// Diagnostic override of the font size that unsized label text starts from;
	// -1 takes it from the diagram's own style.
	private static int forcedNameFontSize = -1;

	public static void main(String[] argv) {
		// Labels are parsed, never drawn: no display or font subsystem is needed.
		if (System.getProperty("java.awt.headless") == null)
			System.setProperty("java.awt.headless", "true");

		final List<String> rest = new ArrayList<>();
		for (final String a : argv) {
			if (a.startsWith("--names=")) {
				namesMode = a.substring("--names=".length());
				if (namesMode.equals(NAMES_RAW) == false && namesMode.equals(NAMES_MARKUP) == false
						&& namesMode.equals(NAMES_DRAWN) == false) {
					System.err.println("Unknown --names value: " + namesMode);
					System.exit(1);
				}
			} else if (a.equals("--no-arrowheads")) {
				emitArrowheads = false;
			} else if (a.equals("--undrawn-edges")) {
				emitUndrawnEdges = true;
			} else if (a.startsWith("--name-font-size=")) {
				forcedNameFontSize = Integer.parseInt(a.substring("--name-font-size=".length()));
			} else {
				rest.add(a);
			}
		}
		final String[] args = rest.toArray(new String[0]);

		if (args.length < 1) {
			System.err.println("Usage: DiagramStatsExtractor [options] <file.puml> [file2.puml ...]");
			System.err.println("       DiagramStatsExtractor [options] --dir <directory>");
			System.err.println("Options: --names=drawn|markup|raw  --no-arrowheads  --undrawn-edges  --name-font-size=<n>");
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

		// Count links by decoration type: the links that are edges below
		int totalConnections = 0;
		for (final Link link : diagram.getLinks()) {
			if (isEdge(link) == false)
				continue;
			totalConnections++;
			final LinkDecor decor1 = link.getType().getDecor1();
			final LinkDecor decor2 = link.getType().getDecor2();
			final String decorKey = classifyLinkDecor(decor1, decor2);
			connections.merge(decorKey, 1, Integer::sum);
		}

		// Named graph: leaf entities as nodes (groups are containers, excluded);
		// the links PlantUML draws as typed edges with display-name endpoints.
		final StringBuilder nodes = new StringBuilder("[");
		boolean firstNode = true;
		for (final Entity entity : diagram.leafs()) {
			final LeafType leafType = entity.getLeafType();
			if (leafType == null)
				continue;
			if (firstNode == false)
				nodes.append(",");
			nodes.append("{\"name\":").append(jsonString(entityName(diagram, entity)))
				.append(",\"type\":").append(jsonString(leafType.name().toLowerCase())).append("}");
			firstNode = false;
		}
		// A childless group (empty "{}" box: rectangle/component/database/empty
		// package) is a single visual element, not a container, so emit it as a
		// node; a prediction that renders the box as a class then matches it
		// instead of scoring a false positive. Groups with children stay
		// containers -- their children are already the nodes.
		for (final Entity group : diagram.groups()) {
			final GroupType groupType = group.getGroupType();
			if (groupType == null || groupType == GroupType.ROOT)
				continue;
			if (group.isEmpty() == false)
				continue;
			if (firstNode == false)
				nodes.append(",");
			nodes.append("{\"name\":").append(jsonString(entityName(diagram, group)))
				.append(",\"type\":").append(jsonString(groupType.name().toLowerCase())).append("}");
			firstNode = false;
		}
		nodes.append("]");

		final StringBuilder edges = new StringBuilder("[");
		boolean firstEdge = true;
		for (final Link link : diagram.getLinks()) {
			if (isEdge(link) == false)
				continue;
			if (firstEdge == false)
				edges.append(",");
			final String relation = canonicalRelation(link.getType());
			final boolean swap = swapEndpoints(relation, link.getType());
			final Entity src = swap ? link.getEntity2() : link.getEntity1();
			final Entity tgt = swap ? link.getEntity1() : link.getEntity2();
			edges.append("{\"source\":").append(jsonString(entityName(diagram, src)))
				.append(",\"target\":").append(jsonString(entityName(diagram, tgt)))
				.append(",\"relation\":").append(jsonString(relation))
				.append(",\"label\":").append(jsonString(flattenDisplay(link.getLabel())));
			if (emitArrowheads && relation.equals("association"))
				edges.append(",\"arrowhead\":").append(jsonString(arrowheadEnd(link.getType())));
			edges.append("}");
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

		// Count messages: the messages that are edges below
		int totalConnections = 0;
		for (final Event event : diagram.events()) {
			if (isHiddenMessage(event))
				continue;
			if (event instanceof Message) {
				totalConnections++;
				connections.merge("message", 1, Integer::sum);
			} else if (event instanceof MessageExo) {
				totalConnections++;
				connections.merge("message_exo", 1, Integer::sum);
			}
		}

		// Named graph: participants as nodes; the messages PlantUML draws as edges
		// (relation "message"). An exo message has only one real participant: the
		// external side is emitted as an empty endpoint.
		final StringBuilder nodes = new StringBuilder("[");
		boolean firstNode = true;
		for (final Participant p : diagram.participants()) {
			if (firstNode == false)
				nodes.append(",");
			nodes.append("{\"name\":").append(jsonString(participantName(diagram, p)))
				.append(",\"type\":").append(jsonString(p.getType().name().toLowerCase())).append("}");
			firstNode = false;
		}
		nodes.append("]");

		final StringBuilder edges = new StringBuilder("[");
		boolean firstEdge = true;
		for (final Event event : diagram.events()) {
			if (isHiddenMessage(event))
				continue;
			String source = null;
			String target = null;
			String label = "";
			if (event instanceof Message) {
				final Message m = (Message) event;
				source = participantName(diagram, m.getParticipant1());
				target = participantName(diagram, m.getParticipant2());
				label = flattenDisplay(m.getLabel());
			} else if (event instanceof MessageExo) {
				final MessageExo x = (MessageExo) event;
				// One endpoint is off the diagram; keep its direction by placing the
				// empty external endpoint on the correct side. A FROM_* arrow enters
				// from the border (external is the sender), a TO_* arrow leaves toward
				// the border (the real participant is the sender).
				final MessageExoType exoType = x.getType();
				final String name = participantName(diagram, x.getParticipant1());
				if (exoType == MessageExoType.FROM_LEFT || exoType == MessageExoType.FROM_RIGHT) {
					source = "";
					target = name;
				} else {
					source = name;
					target = "";
				}
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

	// A link is an edge when PlantUML draws it. PlantUML marks as not drawn
	// (Link.isInvis(), Link.isHidden(), Link.isRemoved()) the links it adds
	// itself to lay out unconnected elements, a link the source writes as
	// [hidden] and a link to an element the source hides or removes. The link of
	// a tip (a note on a class member) to its owner carries the same mark, but
	// the image shows the note's pointer to the member in its place, so it stays
	// an edge.
	private static boolean isEdge(Link link) {
		if (emitUndrawnEdges)
			return true;
		if (link.getEntity1().getLeafType() == LeafType.TIPS || link.getEntity2().getLeafType() == LeafType.TIPS)
			return true;
		return link.isInvis() == false && link.isHidden() == false && link.isRemoved() == false;
	}

	// A message the source writes as [hidden] takes its place in the sequence
	// but is not drawn, so it is not an edge.
	private static boolean isHiddenMessage(Event event) {
		return emitUndrawnEdges == false && event instanceof AbstractMessage
				&& ((AbstractMessage) event).getArrowConfiguration().isHidden();
	}

	// Flatten a Display to a single string by joining its lines as typed. Used
	// for edge labels (not part of any match key) and for names under --names=raw.
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

	// How PlantUML draws the label of an element, which decides how its name is
	// read. PLAIN: the whole label through the markup parser with every markup
	// on (participants, objects, packages). CLASS_HEADER: the header of a
	// class-like element is drawn without `__underline__` and without list items
	// (EntityImageClassHeader, CreoleMode.FULL_BUT_UNDERSCORE). BODY: the label
	// of a note, a use case or a rectangle-like element is drawn by the body
	// builder (BodyEnhanced2), which first cuts it at separator lines.
	private static final int LABEL_PLAIN = 0;
	private static final int LABEL_CLASS_HEADER = 1;
	private static final int LABEL_BODY = 2;

	private static int labelPath(Entity entity) {
		final LeafType leafType = entity.getLeafType();
		if (leafType == null) // a childless group: drawn like a rectangle when it has a symbol
			return entity.getUSymbol() == null ? LABEL_PLAIN : LABEL_BODY;
		if (leafType.isLikeClass())
			return LABEL_CLASS_HEADER;
		if (leafType == LeafType.NOTE || leafType == LeafType.TIPS || leafType == LeafType.DESCRIPTION
				|| leafType == LeafType.USECASE || leafType == LeafType.USECASE_BUSINESS
				|| leafType == LeafType.CIRCLE)
			return LABEL_BODY;
		return LABEL_PLAIN;
	}

	// Name as PlantUML draws it. The label goes through PlantUML's own markup
	// parser under the diagram's own skin, on the path PlantUML draws that kind
	// of element with; a line's text is the concatenation of its text atoms, so
	// formatting markup, icons, sprites and the letter of a stereotype spot are
	// not part of it, while a stereotype label and unknown single-angle text
	// (generics) are. When the lines of a label differ in font size, only the
	// line(s) at the largest size are the name (a name above a smaller package
	// line). `font` is the font unsized text starts from.
	private static String drawnName(Display display, UFont font, ISkinParam skinParam, int labelPath) {
		if (Display.isNull(display))
			return "";
		if (forcedNameFontSize > 0)
			font = font.withSize(forcedNameFontSize);
		final FontConfiguration fc = FontConfiguration.blackBlueTrue(font);
		// The title of a rule is drawn with every markup on, whatever the element.
		final CreoleParser full = new CreoleParser(fc, HorizontalAlignment.LEFT, skinParam, CreoleMode.FULL, fc);
		final CreoleParser parser = labelPath == LABEL_CLASS_HEADER
				? new CreoleParser(fc, HorizontalAlignment.LEFT, skinParam, CreoleMode.FULL_BUT_UNDERSCORE, fc)
				: full;
		final List<String> texts = new ArrayList<>();
		final List<Integer> sizes = new ArrayList<>();
		if (labelPath == LABEL_BODY)
			collectBodyLines(full, skinParam, display, texts, sizes);
		else
			collectDrawnLines(parser, full, skinParam, display, texts, sizes);

		int largest = 0;
		for (final int size : sizes)
			largest = Math.max(largest, size);
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < texts.size(); i++) {
			if (namesMode.equals(NAMES_DRAWN) && sizes.get(i) != largest)
				continue;
			if (sb.length() > 0)
				sb.append(" ");
			sb.append(texts.get(i));
		}
		return sb.toString().trim();
	}

	// A separator title that holds nothing but separator characters is part of
	// the drawn rule, not text.
	private static final Pattern SEPARATOR_CHARACTERS_ONLY = Pattern.compile("[-=._\\s]*");

	// The body builder's reading of a label (BodyEnhanced2.getArea): a separator
	// line (`--`, `==`, `..`, `__`, alone or around a title) is a drawn rule that
	// cuts the label into blocks, and each block goes through the markup parser
	// on its own. An embedded diagram is kept whole inside its block.
	private static void collectBodyLines(CreoleParser parser, ISkinParam skinParam, Display display,
			List<String> texts, List<Integer> sizes) {
		Display block = Display.empty();
		final Iterator<CharSequence> it = display.iterator();
		while (it.hasNext()) {
			final CharSequence s = it.next();
			if (s == null)
				continue;
			if (EmbeddedDiagram.getEmbeddedType(StringUtils.trinNoTrace(s)) != null) {
				block = block.add(s);
				int nested = 1;
				while (nested > 0 && it.hasNext()) {
					final CharSequence inner = it.next();
					if (inner == null)
						continue;
					block = block.add(inner);
					if (EmbeddedDiagram.getEmbeddedType(StringUtils.trinNoTrace(inner)) != null)
						nested++;
					else if (StringUtils.trinNoTrace(inner).equals(EmbeddedDiagram.EMBEDDED_END))
						nested--;
				}
			} else if (BodyEnhancedAbstract.isBlockSeparator(s)) {
				collectDrawnLines(parser, parser, skinParam, block, texts, sizes);
				block = Display.empty();
				final String line = s.toString();
				if (line.length() > 4) {
					final String title = StringUtils.trin(line.substring(2, line.length() - 2));
					if (SEPARATOR_CHARACTERS_ONLY.matcher(title).matches() == false)
						collectDrawnLines(parser, parser, skinParam,
								Display.getWithNewlines(skinParam.getPragma(), title), texts, sizes);
				}
			} else {
				block = block.add(s);
			}
		}
		collectDrawnLines(parser, parser, skinParam, block, texts, sizes);
	}

	// One entry per drawn line that carries text: the text and the largest font
	// size among its text atoms. The title of a rule (`==Title==`) is drawn
	// inside the rule and counts as a line; `titleParser` reads it.
	private static void collectDrawnLines(CreoleParser parser, CreoleParser titleParser, ISkinParam skinParam,
			Display display, List<String> texts, List<Integer> sizes) {
		final Sheet sheet = parser.createSheet(display);
		for (final Stripe stripe : sheet) {
			final StringBuilder text = new StringBuilder();
			int size = 0;
			for (final Atom atom : stripe.getAtoms()) {
				if (atom instanceof AtomText) {
					final AtomText atomText = (AtomText) atom;
					text.append(atomText.getText());
					size = Math.max(size, atomText.getFontConfiguration().getFont().getSize());
				} else if (atom instanceof CreoleHorizontalLine) {
					final String title = ((CreoleHorizontalLine) atom).getTitleLine();
					if (title.length() > 0)
						collectDrawnLines(titleParser, titleParser, skinParam,
								Display.getWithNewlines(skinParam.getPragma(), title), texts, sizes);
				}
			}
			if (text.toString().trim().isEmpty() == false) {
				texts.add(text.toString());
				sizes.add(size);
			}
		}
	}

	// The font PlantUML starts an element's label from, resolved from the
	// diagram's own style (skin defaults plus any skinparam/style in the source).
	private static UFont nameFont(CucaDiagram diagram, Entity entity) {
		final SName diagramName = diagram.getUmlDiagramType().getStyleName();
		final LeafType leafType = entity.getLeafType();
		final StyleSignatureBasic signature;
		if (leafType == LeafType.NOTE || leafType == LeafType.TIPS)
			signature = StyleSignatureBasic.of(SName.root, SName.element, diagramName, SName.note);
		else if (leafType != null && diagram.getUmlDiagramType() == UmlDiagramType.CLASS)
			signature = StyleSignatureBasic.of(SName.root, SName.element, SName.classDiagram, SName.class_,
					SName.header);
		else
			signature = StyleSignatureBasic.of(SName.root, SName.element, diagramName);
		return signature.withTOBECHANGED(entity.getStereotype()).with(entity.getStereostyles())
				.getMergedStyle(diagram.getSkinParam().getCurrentStyleBuilder()).getUFont();
	}

	// Node identity is the visible display name; fall back to the code/alias.
	private static String entityName(CucaDiagram diagram, Entity entity) {
		if (entity == null)
			return "";
		String s = namesMode.equals(NAMES_RAW) ? flattenDisplay(entity.getDisplay())
				: drawnName(entity.getDisplay(), nameFont(diagram, entity), diagram.getSkinParam(),
						labelPath(entity));
		if (s.isEmpty())
			s = entity.getName();
		return s == null ? "" : s;
	}

	private static String participantName(SequenceDiagram diagram, Participant p) {
		if (p == null)
			return "";
		String s = namesMode.equals(NAMES_RAW) ? flattenDisplay(p.getDisplay(false))
				: drawnName(p.getDisplay(false), p.getUsedStyles()[0].getUFont(), diagram.getSkinParam(),
						LABEL_PLAIN);
		if (s.isEmpty())
			s = p.getCode();
		return s == null ? "" : s;
	}

	private static boolean isInheritanceDecor(LinkDecor d) {
		return d == LinkDecor.EXTENDS || d == LinkDecor.REDEFINES || d == LinkDecor.DEFINEDBY;
	}

	private static boolean isDiamondDecor(LinkDecor d) {
		return d == LinkDecor.COMPOSITION || d == LinkDecor.AGREGATION;
	}

	private static boolean isArrowHeadDecor(LinkDecor d) {
		return d == LinkDecor.ARROW || d == LinkDecor.ARROW_TRIANGLE;
	}

	// Which end of an association carries an arrowhead, relative to the emitted
	// source/target (an association keeps entity order, see swapEndpoints):
	// "none", "source", "target" or "both". getDecor2() is the decoration adjacent
	// to entity1, getDecor1() the one adjacent to entity2.
	private static String arrowheadEnd(LinkType type) {
		final boolean atSource = isArrowHeadDecor(type.getDecor2());
		final boolean atTarget = isArrowHeadDecor(type.getDecor1());
		if (atSource && atTarget)
			return "both";
		if (atTarget)
			return "target";
		if (atSource)
			return "source";
		return "none";
	}

	// Canonical edge orientation derived from the link decoration, not from source
	// token order, so two snippets that render the same relationship (e.g. A *-- B
	// and B --* A) emit the same edge. The decorated end identifies the
	// parent/whole/head; the rules follow evaluation-framework.md 2.2/4:
	// inheritance child->parent, composition/aggregation whole->part, dependency
	// tail->head. PlantUML reports getDecor1()/getDecor2() crossed relative to
	// getEntity1()/getEntity2(), so decor2 is the decoration adjacent to entity1.
	// Association and message carry no decoration-derived orientation and keep
	// entity order.
	private static boolean swapEndpoints(String relation, LinkType type) {
		final LinkDecor d1 = type.getDecor1();
		final LinkDecor d2 = type.getDecor2();
		switch (relation) {
			case "inheritance":  // parent carries the triangle; source = child (other end)
				return isInheritanceDecor(d2) && isInheritanceDecor(d1) == false;
			case "composition":
			case "aggregation":  // whole carries the diamond; source = whole
				return isDiamondDecor(d1) && isDiamondDecor(d2) == false;
			case "dependency":   // head carries the arrow; source = tail (other end)
				return isArrowHeadDecor(d2) && isArrowHeadDecor(d1) == false;
			default:
				return false;
		}
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
