package com.bootshift.stages.stage03;

import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.graph.EdgeType;
import com.bootshift.core.graph.GraphEdge;
import com.bootshift.core.graph.GraphNode;
import com.bootshift.core.graph.NodeType;
import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.identity.FileRole;
import com.bootshift.core.util.Ids;
import com.bootshift.ports.analysis.CodeModelPort;
import com.bootshift.ports.build.BuildSystemPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the typed, multi-view application graph from code facts, build facts and configuration
 * (spec section 13).
 *
 * <p>Separated from the stage so the same builder can be reused by Agent 14 for the post-compile
 * full rebuild. V1 always rebuilds fully: incremental graph mutation is a correctness risk that is
 * only worth taking once a graph-equivalence test proves the two agree (ADR-005).
 */
public final class GraphBuilder {

    /** Everything the builder needs, gathered by the calling stage. */
    public record Input(FileRegistry registry,
                        BuildSystemPort.BuildModel buildModel,
                        Map<String, CodeModelPort.AnalysisResult> analysisByModule,
                        Map<String, String> configurationFiles,
                        String label) {
    }

    /** Symbol registry entry (spec section 13, "Symbol identity"). */
    public record SymbolRecord(String symbolId, String fileId, String module, String packageName,
                               String fqn, String kind, String signature, int lineStart, int lineEnd,
                               List<String> annotations, String attribution) {
    }

    public record Result(ApplicationGraph graph, List<SymbolRecord> symbols, List<String> issues,
                         double attributionRatio, int typesModelled) {
    }

    private static final Pattern REQUEST_MAPPING = Pattern.compile(
            "(?:Get|Post|Put|Delete|Patch|Request)Mapping\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']*)[\"']");
    private static final Pattern MAPPING_ANY = Pattern.compile(
            "(Get|Post|Put|Delete|Patch|Request)Mapping");
    private static final Pattern PROPERTY_PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)");
    private static final Pattern DOCUMENT_VALUE = Pattern.compile("Document\\([^)]*[\"']([^\"']+)[\"']");
    private static final Pattern TABLE_VALUE = Pattern.compile("Table\\([^)]*name\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern PROFILE_VALUE = Pattern.compile("Profile\\([^)]*[\"']([^\"']+)[\"']");
    private static final Pattern SCHEDULED = Pattern.compile("Scheduled\\(");

    private final Map<String, GraphNode> typeNodesByFqn = new LinkedHashMap<>();
    private final Map<String, GraphNode> typeNodesBySimpleName = new LinkedHashMap<>();
    private final List<SymbolRecord> symbols = new ArrayList<>();
    private final List<String> issues = new ArrayList<>();

    public Result build(Input input) {
        ApplicationGraph graph = new ApplicationGraph().label(input.label());

        // ---- module nodes from the authoritative build model -------------------------
        for (BuildSystemPort.ModuleModel module : input.buildModel().modules()) {
            GraphNode node = new GraphNode("MODULE:" + module.moduleId(), NodeType.MODULE, module.moduleId());
            node.setModule(module.moduleId())
                    .setFqn(module.groupId() + ":" + module.artifactId())
                    .property("packaging", module.packaging())
                    .property("java_version", module.javaVersion())
                    .property("parent", module.parentGav())
                    .property("version", module.version());
            graph.addNode(node);
        }

        // ---- library nodes ------------------------------------------------------------
        Set<String> libraries = new LinkedHashSet<>();
        for (BuildSystemPort.ResolvedDependency dependency : input.buildModel().dependencies()) {
            String libraryId = "LIB:" + dependency.ga();
            if (libraries.add(libraryId)) {
                GraphNode node = new GraphNode(libraryId, NodeType.LIBRARY, dependency.artifactId());
                node.setFqn(dependency.ga())
                        .property("version", dependency.version())
                        .property("scope", dependency.scope())
                        .property("resolution_status", dependency.resolutionStatus());
                if (!"RESOLVED".equals(dependency.resolutionStatus())) {
                    node.setAttribution(GraphNode.Attribution.UNRESOLVED);
                }
                graph.addNode(node);
            }
            String moduleId = "MODULE:" + dependency.module();
            if (graph.node(moduleId).isPresent()) {
                GraphEdge edge = new GraphEdge(moduleId, EdgeType.DEPENDS_ON_LIBRARY, libraryId,
                        "LibraryDependencyGraph");
                edge.property("scope", dependency.scope())
                        .property("version", dependency.version())
                        .property("direct", dependency.direct());
                graph.addEdge(edge);
            }
        }

        // ---- file nodes ---------------------------------------------------------------
        for (FileRecord record : input.registry().active()) {
            GraphNode node = new GraphNode("FILE:" + record.getFileId(), NodeType.FILE,
                    fileName(record.getCurrentPath()));
            node.setFileId(record.getFileId())
                    .setModule(record.getModule())
                    .setFqn(record.getCurrentPath())
                    .property("role", record.getRole().name())
                    .property("sha256", record.getCurrentSha256());
            graph.addNode(node);
            String moduleId = "MODULE:" + record.getModule();
            if (graph.node(moduleId).isPresent()) {
                graph.addEdge(new GraphEdge(moduleId, EdgeType.CONTAINS, node.getId(), "FileGraph"));
            }
        }

        // ---- type and member nodes ----------------------------------------------------
        int typeCount = 0;
        double weightedAttribution = 0;
        int analysedModules = 0;
        for (Map.Entry<String, CodeModelPort.AnalysisResult> entry : input.analysisByModule().entrySet()) {
            CodeModelPort.AnalysisResult analysis = entry.getValue();
            analysedModules++;
            weightedAttribution += analysis.attributionRatio();
            analysis.issues().forEach(i -> issues.add(i.severity() + " " + i.filePath() + ": " + i.detail()));
            for (CodeModelPort.TypeFacts type : analysis.types()) {
                typeCount++;
                addType(graph, input.registry(), entry.getKey(), type);
            }
        }

        // ---- relationships that need every type node to exist first --------------------
        for (CodeModelPort.AnalysisResult analysis : input.analysisByModule().values()) {
            for (CodeModelPort.TypeFacts type : analysis.types()) {
                linkType(graph, type);
            }
        }

        // ---- configuration, persistence, integration views ----------------------------
        buildConfigurationView(graph, input);
        buildIntegrationView(graph, input);

        double attribution = analysedModules == 0 ? 1.0 : weightedAttribution / analysedModules;
        return new Result(graph, symbols, issues, attribution, typeCount);
    }

    // ------------------------------------------------------------------ type modelling

    private void addType(ApplicationGraph graph, FileRegistry registry, String moduleId,
                         CodeModelPort.TypeFacts type) {
        String fileId = resolveFileId(registry, type.filePath());
        NodeType nodeType = classifyType(type);
        String nodeId = "TYPE:" + type.fqn();
        String symbolId = Ids.symbolId();

        GraphNode node = new GraphNode(nodeId, nodeType, type.simpleName());
        node.setFqn(type.fqn())
                .setPackageName(type.packageName())
                .setModule(moduleId)
                .setFileId(fileId)
                .setSymbolId(symbolId)
                .setLineStart(type.lineStart())
                .setLineEnd(type.lineEnd())
                .setAttribution(type.attribution())
                .setSignature(type.kind() + " " + type.fqn());
        type.annotations().forEach(node::annotation);
        graph.addNode(node);
        typeNodesByFqn.put(type.fqn(), node);
        typeNodesBySimpleName.putIfAbsent(type.simpleName(), node);

        symbols.add(new SymbolRecord(symbolId, fileId, moduleId, type.packageName(), type.fqn(),
                nodeType.name(), node.getSignature(), type.lineStart(), type.lineEnd(),
                type.annotations(), type.attribution().name()));
        if (fileId != null) {
            registry.attachSymbol(fileId, symbolId);
            graph.addEdge(new GraphEdge("FILE:" + fileId, EdgeType.DECLARES, nodeId, "FileGraph"));
        }

        String packageId = "PACKAGE:" + type.packageName();
        graph.addNode(new GraphNode(packageId, NodeType.PACKAGE, type.packageName())
                .setModule(moduleId).setFqn(type.packageName()));
        graph.addEdge(new GraphEdge(packageId, EdgeType.CONTAINS, nodeId, "SymbolGraph"));

        boolean controller = nodeType == NodeType.CONTROLLER;
        List<CodeModelPort.MemberFacts> declaredFields = new ArrayList<>();
        for (CodeModelPort.MemberFacts member : type.members()) {
            String memberId = nodeId + "#" + member.name() + memberSuffix(member);
            NodeType memberType = switch (member.kind()) {
                case "FIELD" -> NodeType.FIELD;
                case "CONSTRUCTOR" -> NodeType.CONSTRUCTOR;
                default -> NodeType.METHOD;
            };
            String memberSymbolId = Ids.symbolId();
            GraphNode memberNode = new GraphNode(memberId, memberType, member.name());
            memberNode.setFqn(type.fqn() + "." + member.name())
                    .setModule(moduleId)
                    .setFileId(fileId)
                    .setSymbolId(memberSymbolId)
                    .setPackageName(type.packageName())
                    .setSignature(member.signature())
                    .setLineStart(member.lineStart())
                    .setLineEnd(member.lineEnd())
                    .setAttribution(member.attribution());
            member.annotations().forEach(memberNode::annotation);
            graph.addNode(memberNode);
            graph.addEdge(new GraphEdge(nodeId, EdgeType.DECLARES, memberId, "SymbolGraph"));
            symbols.add(new SymbolRecord(memberSymbolId, fileId, moduleId, type.packageName(),
                    type.fqn() + "." + member.name(), memberType.name(), member.signature(),
                    member.lineStart(), member.lineEnd(), member.annotations(),
                    member.attribution().name()));
            if (fileId != null) {
                registry.attachSymbol(fileId, memberSymbolId);
            }

            if (controller) {
                addEndpoint(graph, type, member, nodeId, memberId, moduleId, fileId);
            }
            if ("FIELD".equals(member.kind())) {
                declaredFields.add(member);
            }
            if (member.annotations().stream().anyMatch(a -> SCHEDULED.matcher(a).find())) {
                String scheduleId = "EXTERNAL:scheduler";
                graph.addNode(new GraphNode(scheduleId, NodeType.EXTERNAL_SYSTEM, "scheduler"));
                graph.addEdge(new GraphEdge(memberId, EdgeType.CALLS_EXTERNAL_SERVICE, scheduleId,
                        "IntegrationGraph").property("mechanism", "scheduled"));
            }
        }

        addAnnotationProcessorMembers(graph, type, nodeId, moduleId, fileId, declaredFields);
    }

    /**
     * Models members that an annotation processor generates at compile time.
     *
     * <p>Lombok members do not exist in the source, so a source-level parser cannot resolve a call to
     * {@code employee.getName()} or {@code log.trace(...)}. Leaving them out would silently remove
     * most service-to-model relationships from the graph, so they are synthesized and marked
     * {@code synthetic=true} with the processor that produces them. Nothing pretends they were parsed.
     */
    private void addAnnotationProcessorMembers(ApplicationGraph graph, CodeModelPort.TypeFacts type,
                                               String nodeId, String moduleId, String fileId,
                                               List<CodeModelPort.MemberFacts> declaredFields) {
        List<String> annotations = type.annotations();
        boolean getters = annotations.stream().anyMatch(a -> a.startsWith("Data")
                || a.startsWith("Getter") || a.startsWith("Value"));
        boolean setters = annotations.stream().anyMatch(a -> a.startsWith("Data")
                || a.startsWith("Setter"));
        boolean builder = annotations.stream().anyMatch(a -> a.startsWith("Builder"));
        boolean logger = annotations.stream().anyMatch(a -> a.startsWith("Slf4j")
                || a.startsWith("Log4j2") || a.startsWith("CommonsLog"));

        if (getters || setters) {
            for (CodeModelPort.MemberFacts field : declaredFields) {
                String capitalized = capitalize(field.name());
                boolean bool = "boolean".equals(field.returnType());
                if (getters) {
                    synthesize(graph, nodeId, moduleId, fileId, type,
                            (bool ? "is" : "get") + capitalized, "()", field.returnType(), "lombok");
                }
                if (setters) {
                    synthesize(graph, nodeId, moduleId, fileId, type,
                            "set" + capitalized, "(" + field.returnType() + ")", "void", "lombok");
                }
            }
        }
        if (builder) {
            synthesize(graph, nodeId, moduleId, fileId, type, "builder", "()",
                    type.simpleName() + "Builder", "lombok");
        }
        if (logger) {
            String memberId = nodeId + "#log";
            GraphNode node = new GraphNode(memberId, NodeType.FIELD, "log");
            node.setFqn(type.fqn() + ".log").setModule(moduleId).setFileId(fileId)
                    .setSignature("org.slf4j.Logger log")
                    .setAttribution(GraphNode.Attribution.RESOLVED)
                    .property("synthetic", true)
                    .property("generated_by", "lombok");
            graph.addNode(node);
            graph.addEdge(new GraphEdge(nodeId, EdgeType.DECLARES, memberId, "SymbolGraph")
                    .property("synthetic", true));
        }
    }

    private void synthesize(ApplicationGraph graph, String nodeId, String moduleId, String fileId,
                            CodeModelPort.TypeFacts type, String name, String parameters,
                            String returnType, String processor) {
        String memberId = nodeId + "#" + name + parameters;
        GraphNode node = new GraphNode(memberId, NodeType.METHOD, name);
        node.setFqn(type.fqn() + "." + name).setModule(moduleId).setFileId(fileId)
                .setSignature(returnType + " " + name + parameters)
                .setLineStart(type.lineStart()).setLineEnd(type.lineEnd())
                .setAttribution(GraphNode.Attribution.RESOLVED)
                .property("synthetic", true)
                .property("generated_by", processor);
        graph.addNode(node);
        graph.addEdge(new GraphEdge(nodeId, EdgeType.DECLARES, memberId, "SymbolGraph")
                .property("synthetic", true));
    }

    private static String capitalize(String value) {
        return value == null || value.isEmpty() ? value
                : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private void linkType(ApplicationGraph graph, CodeModelPort.TypeFacts type) {
        String nodeId = "TYPE:" + type.fqn();
        if (graph.node(nodeId).isEmpty()) {
            return;
        }

        if (type.superType() != null) {
            resolveType(type.superType()).ifPresent(target ->
                    graph.addEdge(new GraphEdge(nodeId, EdgeType.EXTENDS, target.getId(), "SymbolGraph")));
        }
        for (String iface : type.interfaces()) {
            resolveType(iface).ifPresent(target ->
                    graph.addEdge(new GraphEdge(nodeId, EdgeType.IMPLEMENTS, target.getId(), "SymbolGraph")));
        }
        for (String imported : type.importedTypes()) {
            resolveType(imported).ifPresent(target -> {
                if (!target.getId().equals(nodeId)) {
                    graph.addEdge(new GraphEdge(nodeId, EdgeType.IMPORTS, target.getId(), "SymbolGraph"));
                }
            });
        }
        for (String referenced : type.referencedTypes()) {
            resolveType(referenced).ifPresent(target -> {
                if (!target.getId().equals(nodeId)) {
                    graph.addEdge(new GraphEdge(nodeId, EdgeType.USES_TYPE, target.getId(), "SymbolGraph"));
                }
            });
        }

        // Dependency injection: fields and constructor parameters typed as application beans.
        for (CodeModelPort.MemberFacts member : type.members()) {
            boolean injected = member.annotations().stream()
                    .anyMatch(a -> a.startsWith("Autowired") || a.startsWith("Inject")
                            || a.startsWith("Resource"));
            List<String> candidateTypes = new ArrayList<>(member.usedTypes());
            if (member.returnType() != null) {
                candidateTypes.add(member.returnType());
            }
            candidateTypes.addAll(member.parameterTypes());
            for (String candidate : candidateTypes) {
                resolveType(candidate).ifPresent(target -> {
                    if (target.getId().equals(nodeId)) {
                        return;
                    }
                    boolean bean = isBeanNode(target);
                    if (bean && (injected || "CONSTRUCTOR".equals(member.kind()))) {
                        graph.addEdge(new GraphEdge(nodeId, EdgeType.INJECTS, target.getId(), "SpringGraph"));
                    }
                    if (bean) {
                        EdgeType semantic = switch (target.getType()) {
                            case SERVICE -> EdgeType.CALLS_SERVICE;
                            case REPOSITORY -> EdgeType.CALLS_REPOSITORY;
                            default -> null;
                        };
                        if (semantic != null) {
                            graph.addEdge(new GraphEdge(nodeId, semantic, target.getId(), "SpringGraph"));
                        }
                    }
                });
            }
            String memberId = nodeId + "#" + member.name() + memberSuffix(member);
            for (String call : member.calls()) {
                if (call.startsWith("?.")) {
                    linkUnresolvedCall(graph, memberId, call.substring(2), member, type);
                    continue;
                }
                int lastDot = call.lastIndexOf('.');
                if (lastDot <= 0) {
                    continue;
                }
                String ownerFqn = call.substring(0, lastDot);
                String methodName = call.substring(lastDot + 1);
                GraphNode owner = typeNodesByFqn.get(ownerFqn);
                if (owner == null) {
                    continue;
                }
                String targetMember = owner.getId() + "#" + methodName;
                Optional<GraphNode> exact = graph.node(targetMember);
                String resolvedTarget = exact.map(GraphNode::getId).orElseGet(() ->
                        graph.nodesMatching(n -> n.getId().startsWith(targetMember + "("))
                                .stream().findFirst().map(GraphNode::getId).orElse(null));
                if (resolvedTarget != null) {
                    graph.addEdge(new GraphEdge(memberId, EdgeType.CALLS, resolvedTarget, "SymbolGraph"));
                }
            }
        }

        // Repository to entity: generic parameters of the Spring Data interface.
        if (isRepository(type)) {
            for (String referenced : type.referencedTypes()) {
                resolveType(referenced).ifPresent(target -> {
                    if (target.getType() == NodeType.ENTITY || target.getType() == NodeType.MONGODB_DOCUMENT) {
                        graph.addEdge(new GraphEdge(nodeId, EdgeType.MANAGES_ENTITY, target.getId(),
                                "PersistenceGraph"));
                    }
                });
            }
        }

        // Test coverage relationships.
        if (isTest(type)) {
            for (String referenced : type.referencedTypes()) {
                resolveType(referenced).ifPresent(target -> {
                    if (isBeanNode(target) || target.getType().isTypeDeclaration()) {
                        graph.addEdge(new GraphEdge(nodeId, EdgeType.COVERED_BY_TEST, target.getId(),
                                "TestGraph").setConfidence(0.7)
                                .property("basis", "type reference from test source"));
                    }
                });
            }
        }
    }

    /**
     * Recovers a call the type solver could not attribute.
     *
     * <p>Only types this method demonstrably touches are considered as receivers, and a link is made
     * only when exactly one of them declares a member with that name. The edge is recorded at low
     * confidence with the basis attached, so Agent 09 caps any impact derived from it rather than
     * treating a heuristic as a resolved fact.
     */
    private void linkUnresolvedCall(ApplicationGraph graph, String memberId, String methodName,
                                    CodeModelPort.MemberFacts member, CodeModelPort.TypeFacts owner) {
        List<String> candidates = new ArrayList<>();
        Set<String> scope = new LinkedHashSet<>(member.usedTypes());
        scope.addAll(member.parameterTypes());
        scope.addAll(owner.members().stream()
                .filter(m -> "FIELD".equals(m.kind()))
                .map(CodeModelPort.MemberFacts::returnType)
                .filter(java.util.Objects::nonNull)
                .toList());
        for (String candidate : scope) {
            resolveType(candidate).ifPresent(target -> {
                String prefix = target.getId() + "#" + methodName;
                graph.nodesMatching(n -> n.getId().equals(prefix) || n.getId().startsWith(prefix + "("))
                        .forEach(n -> candidates.add(n.getId()));
            });
        }
        List<String> distinct = candidates.stream().distinct().toList();
        if (distinct.size() != 1) {
            return;
        }
        graph.addEdge(new GraphEdge(memberId, EdgeType.CALLS, distinct.get(0), "SymbolGraph")
                .setConfidence(0.5)
                .property("basis", "unique-name-in-visible-type-scope heuristic")
                .property("attribution", "UNRESOLVED_BY_TYPE_SOLVER"));
    }

    private void addEndpoint(ApplicationGraph graph, CodeModelPort.TypeFacts type,
                             CodeModelPort.MemberFacts member, String typeNodeId, String memberId,
                             String moduleId, String fileId) {
        String classPath = type.annotations().stream()
                .map(REQUEST_MAPPING::matcher)
                .filter(Matcher::find)
                .map(m -> m.group(1))
                .findFirst()
                .orElse("");
        for (String annotation : member.annotations()) {
            Matcher any = MAPPING_ANY.matcher(annotation);
            if (!any.find()) {
                continue;
            }
            String method = any.group(1).toUpperCase(Locale.ROOT);
            if ("REQUEST".equals(method)) {
                method = "ANY";
            }
            Matcher path = REQUEST_MAPPING.matcher(annotation);
            String methodPath = path.find() ? path.group(1) : "";
            String full = join(classPath, methodPath);
            String endpointId = "ENDPOINT:" + method + " " + full;
            GraphNode endpoint = new GraphNode(endpointId, NodeType.ENDPOINT, method + " " + full);
            endpoint.setModule(moduleId).setFileId(fileId).setFqn(full)
                    .property("http_method", method)
                    .property("path", full)
                    .property("handler", type.fqn() + "." + member.name());
            graph.addNode(endpoint);
            graph.addEdge(new GraphEdge(memberId, EdgeType.HANDLES_ENDPOINT, endpointId, "EndpointGraph"));
            graph.addEdge(new GraphEdge(typeNodeId, EdgeType.CONTAINS, endpointId, "EndpointGraph"));
        }
    }

    // ------------------------------------------------------------------ config and integration

    private void buildConfigurationView(ApplicationGraph graph, Input input) {
        for (Map.Entry<String, String> entry : input.configurationFiles().entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue();
            Optional<FileRecord> record = input.registry().byPath(path);
            String fileId = record.map(FileRecord::getFileId).orElse(null);
            String module = record.map(FileRecord::getModule).orElse(moduleOf(path));

            if (path.endsWith(".properties")) {
                for (String rawLine : content.split("\n")) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                        continue;
                    }
                    int separator = line.indexOf('=');
                    if (separator <= 0) {
                        continue;
                    }
                    addConfigProperty(graph, module, fileId, path, line.substring(0, separator).trim(),
                            line.substring(separator + 1).trim());
                }
            } else if (path.endsWith(".yml") || path.endsWith(".yaml")) {
                flattenYaml(content).forEach((key, value) ->
                        addConfigProperty(graph, module, fileId, path, key, value));
            }
        }

        // Types that reference a property placeholder use that property.
        for (Map.Entry<String, GraphNode> entry : typeNodesByFqn.entrySet()) {
            GraphNode node = entry.getValue();
            for (String annotation : node.getAnnotations()) {
                Matcher matcher = PROPERTY_PLACEHOLDER.matcher(annotation);
                while (matcher.find()) {
                    String key = matcher.group(1).trim();
                    String propertyId = "PROPERTY:" + key;
                    graph.addNode(new GraphNode(propertyId, NodeType.CONFIG_PROPERTY, key).setFqn(key));
                    graph.addEdge(new GraphEdge(node.getId(), EdgeType.USES_CONFIG_PROPERTY, propertyId,
                            "ConfigurationGraph"));
                }
                Matcher profile = PROFILE_VALUE.matcher(annotation);
                if (profile.find()) {
                    String profileId = "PROFILE:" + profile.group(1);
                    graph.addNode(new GraphNode(profileId, NodeType.PROFILE, profile.group(1)));
                    graph.addEdge(new GraphEdge(profileId, EdgeType.ACTIVATED_BY_PROFILE, node.getId(),
                            "ConfigurationGraph"));
                }
            }
        }
    }

    private void addConfigProperty(ApplicationGraph graph, String module, String fileId, String path,
                                   String key, String value) {
        String propertyId = "PROPERTY:" + key;
        GraphNode node = new GraphNode(propertyId, NodeType.CONFIG_PROPERTY, key);
        node.setFqn(key).setModule(module)
                .property("source_file", path)
                .property("sensitive", com.bootshift.core.security.SensitiveValues.isSensitiveKey(key));
        graph.addNode(node);
        if (fileId != null && graph.node("FILE:" + fileId).isPresent()) {
            graph.addEdge(new GraphEdge("FILE:" + fileId, EdgeType.CONFIGURES, propertyId,
                    "ConfigurationGraph"));
        }
        if (key.startsWith("spring.cloud.config")) {
            graph.addNode(new GraphNode("CONFIG_SERVER:spring-cloud-config", NodeType.CONFIG_SERVER,
                    "spring-cloud-config"));
            graph.addEdge(new GraphEdge("MODULE:" + module, EdgeType.READS_FROM_CONFIG_SERVER,
                    "CONFIG_SERVER:spring-cloud-config", "IntegrationGraph"));
        }
        if (key.startsWith("eureka.")) {
            graph.addNode(new GraphNode("DISCOVERY:eureka", NodeType.DISCOVERY_SERVER, "eureka"));
            graph.addEdge(new GraphEdge("MODULE:" + module, EdgeType.REGISTERS_WITH_DISCOVERY,
                    "DISCOVERY:eureka", "IntegrationGraph"));
        }
        if (key.startsWith("spring.data.mongodb")) {
            graph.addNode(new GraphNode("EXTERNAL:mongodb", NodeType.EXTERNAL_SYSTEM, "mongodb"));
            graph.addEdge(new GraphEdge("MODULE:" + module, EdgeType.CALLS_EXTERNAL_SERVICE,
                    "EXTERNAL:mongodb", "IntegrationGraph"));
        }
        if (key.startsWith("spring.kafka") || key.startsWith("spring.rabbitmq")) {
            String system = key.startsWith("spring.kafka") ? "kafka" : "rabbitmq";
            graph.addNode(new GraphNode("DESTINATION:" + system, NodeType.MESSAGE_DESTINATION, system));
            graph.addEdge(new GraphEdge("MODULE:" + module, EdgeType.PUBLISHES_TO,
                    "DESTINATION:" + system, "IntegrationGraph"));
        }
    }

    private void buildIntegrationView(ApplicationGraph graph, Input input) {
        for (GraphNode node : typeNodesByFqn.values()) {
            boolean webClient = node.getProperties().containsKey("uses_web_client");
            if (webClient) {
                graph.addNode(new GraphNode("EXTERNAL:http", NodeType.EXTERNAL_SYSTEM, "http-client"));
                graph.addEdge(new GraphEdge(node.getId(), EdgeType.CALLS_EXTERNAL_SERVICE,
                        "EXTERNAL:http", "IntegrationGraph"));
            }
        }
        for (BuildSystemPort.ResolvedDependency dependency : input.buildModel().dependencies()) {
            String system = switch (dependency.artifactId()) {
                case "spring-cloud-starter-netflix-eureka-client",
                     "spring-cloud-starter-netflix-eureka-server" -> "eureka";
                case "spring-cloud-starter-config", "spring-cloud-config-server" -> "config-server";
                case "spring-boot-starter-data-mongodb" -> "mongodb";
                case "spring-boot-starter-data-redis" -> "redis";
                case "spring-kafka" -> "kafka";
                case "spring-boot-starter-amqp" -> "rabbitmq";
                default -> null;
            };
            if (system == null) {
                continue;
            }
            NodeType type = switch (system) {
                case "eureka" -> NodeType.DISCOVERY_SERVER;
                case "config-server" -> NodeType.CONFIG_SERVER;
                case "kafka", "rabbitmq" -> NodeType.MESSAGE_DESTINATION;
                default -> NodeType.EXTERNAL_SYSTEM;
            };
            String systemId = type.name() + ":" + system;
            graph.addNode(new GraphNode(systemId, type, system));
            EdgeType edgeType = switch (system) {
                case "eureka" -> EdgeType.REGISTERS_WITH_DISCOVERY;
                case "config-server" -> EdgeType.READS_FROM_CONFIG_SERVER;
                case "kafka", "rabbitmq" -> EdgeType.PUBLISHES_TO;
                default -> EdgeType.CALLS_EXTERNAL_SERVICE;
            };
            graph.addEdge(new GraphEdge("MODULE:" + dependency.module(), edgeType, systemId,
                    "IntegrationGraph").property("evidence", "resolved dependency " + dependency.gav()));
        }
    }

    // ------------------------------------------------------------------ helpers

    private Optional<GraphNode> resolveType(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        String cleaned = reference.replaceAll("<.*>", "").trim();
        GraphNode byFqn = typeNodesByFqn.get(cleaned);
        if (byFqn != null) {
            return Optional.of(byFqn);
        }
        int lastDot = cleaned.lastIndexOf('.');
        String simple = lastDot < 0 ? cleaned : cleaned.substring(lastDot + 1);
        return Optional.ofNullable(typeNodesBySimpleName.get(simple));
    }

    private static boolean isBeanNode(GraphNode node) {
        return node.getType() == NodeType.SERVICE || node.getType() == NodeType.REPOSITORY
                || node.getType() == NodeType.CONTROLLER || node.getType() == NodeType.SPRING_BEAN
                || node.getType() == NodeType.CONFIGURATION_CLASS;
    }

    /** Spring stereotype classification from declared annotations and interface inheritance. */
    public static NodeType classifyType(CodeModelPort.TypeFacts type) {
        List<String> annotations = type.annotations();
        if (annotations.stream().anyMatch(a -> a.startsWith("RestController") || a.startsWith("Controller"))) {
            return NodeType.CONTROLLER;
        }
        if (annotations.stream().anyMatch(a -> a.startsWith("Service"))) {
            return NodeType.SERVICE;
        }
        if (annotations.stream().anyMatch(a -> a.startsWith("Repository")) || isRepository(type)) {
            return NodeType.REPOSITORY;
        }
        if (annotations.stream().anyMatch(a -> a.startsWith("Document"))) {
            return NodeType.MONGODB_DOCUMENT;
        }
        if (annotations.stream().anyMatch(a -> a.startsWith("Entity") || a.startsWith("Table"))) {
            return NodeType.ENTITY;
        }
        if (annotations.stream().anyMatch(a -> a.startsWith("Configuration")
                || a.startsWith("SpringBootApplication") || a.startsWith("EnableAutoConfiguration")
                || a.startsWith("ControllerAdvice") || a.startsWith("RestControllerAdvice"))) {
            return NodeType.CONFIGURATION_CLASS;
        }
        if (annotations.stream().anyMatch(a -> a.startsWith("Component"))) {
            return NodeType.SPRING_BEAN;
        }
        if (isTest(type)) {
            return NodeType.TEST;
        }
        return switch (type.kind()) {
            case "INTERFACE" -> NodeType.INTERFACE;
            case "ENUM" -> NodeType.ENUM;
            case "RECORD" -> NodeType.RECORD;
            default -> NodeType.CLASS;
        };
    }

    private static boolean isRepository(CodeModelPort.TypeFacts type) {
        return type.interfaces().stream().anyMatch(i ->
                i.contains("Repository") || i.contains("CrudRepository") || i.contains("MongoRepository")
                        || i.contains("JpaRepository"));
    }

    private static boolean isTest(CodeModelPort.TypeFacts type) {
        if (type.filePath().contains("/src/test/")) {
            return true;
        }
        return type.members().stream().anyMatch(m -> m.annotations().stream()
                .anyMatch(a -> a.startsWith("Test") || a.startsWith("ParameterizedTest")));
    }

    private String resolveFileId(FileRegistry registry, String absolutePath) {
        String normalized = FileRegistry.normalize(absolutePath);
        Optional<FileRecord> exact = registry.byPath(normalized);
        if (exact.isPresent()) {
            return exact.get().getFileId();
        }
        for (FileRecord record : registry.active()) {
            if (normalized.endsWith(record.getCurrentPath())) {
                return record.getFileId();
            }
        }
        return null;
    }

    /** Flattens nested YAML into dotted keys. Deliberately simple: no anchors, no multi-document. */
    public static Map<String, String> flattenYaml(String content) {
        Map<String, String> flat = new LinkedHashMap<>();
        List<String> stack = new ArrayList<>();
        for (String rawLine : content.split("\n")) {
            if (rawLine.isBlank() || rawLine.trim().startsWith("#") || rawLine.trim().startsWith("-")) {
                continue;
            }
            int indent = rawLine.length() - rawLine.stripLeading().length();
            String line = rawLine.trim();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            int depth = indent / 2;
            while (stack.size() > depth) {
                stack.remove(stack.size() - 1);
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            stack.add(key);
            if (!value.isEmpty()) {
                flat.put(String.join(".", stack), value);
                stack.remove(stack.size() - 1);
            }
        }
        return flat;
    }

    private static String join(String base, String suffix) {
        String left = base == null ? "" : base.replaceAll("/+$", "");
        String right = suffix == null ? "" : suffix.replaceAll("^/+", "");
        if (left.isEmpty()) {
            return "/" + right;
        }
        String prefixed = left.startsWith("/") ? left : "/" + left;
        return right.isEmpty() ? prefixed : prefixed + "/" + right;
    }

    private static String memberSuffix(CodeModelPort.MemberFacts member) {
        return "FIELD".equals(member.kind()) ? "" : "(" + String.join(",", member.parameterTypes()) + ")";
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String moduleOf(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? "." : path.substring(0, slash);
    }

    /** Roles whose content the configuration view reads. */
    public static boolean isConfigurationRole(FileRole role) {
        return role == FileRole.CONFIG_PROPERTIES || role == FileRole.CONFIG_YAML;
    }
}
