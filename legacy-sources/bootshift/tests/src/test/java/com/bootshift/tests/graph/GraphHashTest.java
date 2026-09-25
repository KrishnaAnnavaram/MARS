package com.bootshift.tests.graph;

import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.graph.EdgeType;
import com.bootshift.core.graph.GraphEdge;
import com.bootshift.core.graph.GraphNode;
import com.bootshift.core.graph.NodeType;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.identity.FileRole;
import com.bootshift.core.util.Hashing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hash-comparability tests.
 *
 * <p>Two runs over a byte-identical repository allocate different FILE_IDs, so any hash built over
 * identities differs between them. That is correct and necessary within a run - it is what detects
 * identity churn - and it is misleading in a report, because a reader comparing two runs concludes
 * the application changed when nothing did. These tests pin down which hash is which.
 */
class GraphHashTest {

    /**
     * Builds the same application twice with different identity prefixes, the way two runs do.
     */
    private ApplicationGraph build(String idPrefix) {
        ApplicationGraph graph = new ApplicationGraph().label("test");

        GraphNode controller = new GraphNode(idPrefix + ":CLASS:Controller", NodeType.CONTROLLER,
                "EmployeeController");
        controller.setFqn("com.example.EmployeeController");
        controller.setFileId(idPrefix + "-FILE-1");
        controller.setAttribution(GraphNode.Attribution.RESOLVED);

        GraphNode service = new GraphNode(idPrefix + ":CLASS:Service", NodeType.SERVICE,
                "EmployeeService");
        service.setFqn("com.example.EmployeeService");
        service.setFileId(idPrefix + "-FILE-2");
        service.setAttribution(GraphNode.Attribution.RESOLVED);

        graph.addNode(controller);
        graph.addNode(service);
        graph.addEdge(new GraphEdge(idPrefix + ":E1", EdgeType.INJECTS,
                controller.getId(), service.getId()));
        return graph;
    }

    @Test
    @DisplayName("the content hash is identical across runs that describe the same application")
    void contentHashIsComparableAcrossRuns() {
        assertThat(build("RUN-A").contentHash()).isEqualTo(build("RUN-B").contentHash());
    }

    @Test
    @DisplayName("the structural hash differs across runs, because it carries identity")
    void structuralHashCarriesIdentity() {
        // Not a defect: this is what makes identity churn detectable inside a run. The defect was
        // reporting only this value and calling it "structural", which reads as comparable.
        assertThat(build("RUN-A").structuralHash()).isNotEqualTo(build("RUN-B").structuralHash());
    }

    @Test
    @DisplayName("the content hash still changes when the application actually changes")
    void contentHashDetectsRealChange() {
        ApplicationGraph original = build("RUN-A");
        ApplicationGraph changed = build("RUN-A");
        GraphNode extra = new GraphNode("RUN-A:CLASS:Repository", NodeType.REPOSITORY,
                "EmployeeRepository");
        extra.setFqn("com.example.EmployeeRepository");
        extra.setAttribution(GraphNode.Attribution.RESOLVED);
        changed.addNode(extra);

        assertThat(changed.contentHash()).isNotEqualTo(original.contentHash());
    }

    @Test
    @DisplayName("an edge rewired to a different target changes the content hash")
    void contentHashCoversEdgeEndpoints() {
        ApplicationGraph original = build("RUN-A");
        ApplicationGraph rewired = new ApplicationGraph().label("test");

        GraphNode controller = new GraphNode("X:1", NodeType.CONTROLLER, "EmployeeController");
        controller.setFqn("com.example.EmployeeController");
        controller.setAttribution(GraphNode.Attribution.RESOLVED);
        GraphNode other = new GraphNode("X:2", NodeType.SERVICE, "EmployeeService");
        other.setFqn("com.example.OtherService");
        other.setAttribution(GraphNode.Attribution.RESOLVED);
        rewired.addNode(controller);
        rewired.addNode(other);
        rewired.addEdge(new GraphEdge("X:E1", EdgeType.INJECTS, controller.getId(), other.getId()));

        assertThat(rewired.contentHash()).isNotEqualTo(original.contentHash());
    }

    private FileRegistry registryOf(String... contents) {
        FileRegistry registry = new FileRegistry();
        int index = 0;
        for (String content : contents) {
            String path = "mod/src/main/java/F" + index++ + ".java";
            registry.allocate(new FileRegistry.ObservedFile("mod", path, Hashing.sha256(content),
                    FileRole.JAVA_MAIN, content.length(), content));
        }
        registry.seal();
        return registry;
    }

    @Test
    @DisplayName("the registry content manifest hash is comparable across runs; the seal hash is not")
    void registryHashesSeparateIdentityFromContent() {
        FileRegistry runA = registryOf("class A {}", "class B {}");
        FileRegistry runB = registryOf("class A {}", "class B {}");

        assertThat(runA.contentManifestHash()).isEqualTo(runB.contentManifestHash());
        assertThat(runA.sealHash()).isNotEqualTo(runB.sealHash());
    }

    @Test
    @DisplayName("the registry content manifest hash changes when a file's content changes")
    void registryContentHashDetectsRealChange() {
        assertThat(registryOf("class A {}", "class B {}").contentManifestHash())
                .isNotEqualTo(registryOf("class A {}", "class B { int x; }").contentManifestHash());
    }
}
