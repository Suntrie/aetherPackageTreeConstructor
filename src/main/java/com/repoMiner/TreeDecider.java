package com.repoMiner;

import kotlin.Pair;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.*;
import org.eclipse.aether.util.artifact.ArtifactIdUtils;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static com.repoMiner.CustomClassLoader.getNextJarEntryMatches;

public class TreeDecider {

    private static RepositorySystem repositorySystem;
    private DefaultRepositorySystemSession defaultRepositorySystemSession;
    private String basePackageCoordinates;

    public TreeDecider(DefaultRepositorySystemSession defaultRepositorySystemSession) {
        this.defaultRepositorySystemSession = defaultRepositorySystemSession;
    }

    public static RepositorySystem getRepositorySystem() {
        return repositorySystem;
    }

    public static void setRepositorySystem(RepositorySystem repositorySystem) {
        TreeDecider.repositorySystem = repositorySystem;
    }

    private CustomClassLoader customClassLoader = new CustomClassLoader();

    // Main function to get the whole graph for the library with coords

    public void makeTree(String coords) throws ArtifactDescriptorException, DependencyCollectionException, IOException, XmlPullParserException, ArtifactResolutionException {

        Artifact artifact = new DefaultArtifact(coords);

        AetherUtils.setFiltering(defaultRepositorySystemSession, false);

        ArtifactDescriptorRequest descriptorRequest = new ArtifactDescriptorRequest();
        descriptorRequest.setArtifact(artifact);
        descriptorRequest.setRepositories(AetherUtils.newRemoteRepositories(repositorySystem,
                defaultRepositorySystemSession));

        ArtifactDescriptorResult descriptorResult = repositorySystem.readArtifactDescriptor(defaultRepositorySystemSession,
                descriptorRequest); // чтение прямых зависимостей (дескриптор артефакта - POM)

        AetherUtils.setFiltering(defaultRepositorySystemSession, true);

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRootArtifact(descriptorResult.getArtifact()); // Этого недостаточно для root NodeDependency dependency <>null
        collectRequest.setDependencies(descriptorResult.getDependencies());
        collectRequest.setManagedDependencies(descriptorResult.getManagedDependencies());
        collectRequest.setRepositories(descriptorRequest.getRepositories());

        // получение транзитивных зависимостей
        CollectResult collectResult = repositorySystem.collectDependencies(defaultRepositorySystemSession, collectRequest);

        DependencyNode rootNode = collectResult.getRoot();

        // разметка дерева транзитивных зависимостей: каждой ноде сопоставляются реально соответствующие ей библиотеки
        List<Pair<DependencyNode, Set<String>>> markedNodes = markTree(rootNode);

        loadDependencies(markedNodes);
    }


    // Checker whether the node is winner according Maven rules

    private boolean isWinnerNode(DependencyNode dependencyNode) {

        DependencyNode currentNodeWinner = (DependencyNode) dependencyNode.
                getData().get(ConflictResolver.NODE_DATA_WINNER);

        return currentNodeWinner == null;
    }

    // Utility function to get representation of coords of nested libraries as well as base library

    private Set<String> getNestedPomDependenciesCoordsForExclusion(Artifact artifact)
            throws IOException, XmlPullParserException {

        JarFile jar = new JarFile(artifact.getFile());
        JarEntry jarEntry;

        Enumeration<JarEntry> jarEntryEnumeration = jar.entries();

        Set<String> dependencies = new HashSet<>();

        while (true) {

            jarEntry = getNextJarEntryMatches(jarEntryEnumeration, "pom.xml");       //filter target

            if (jarEntry == null) {
                break;
            }

            MavenXpp3Reader reader = new MavenXpp3Reader();

            InputStream jarInputStream = jar
                    .getInputStream(jarEntry);

            Model model = reader.read(jarInputStream);

            String coords = model.getGroupId() + ":" + model.getArtifactId();

            dependencies.add(coords);
        }

        return dependencies;
    }

    // Markation of all nodes with their respectful libs
    // Breadth First Traversal

    private List<Pair<DependencyNode, Set<String>>> markTree(DependencyNode rootNode) throws IOException, XmlPullParserException, ArtifactResolutionException {

        Set<String> checkedLibraries = new HashSet<>();

        List<Pair<DependencyNode, Set<String>>> markedLayeredNodes = new ArrayList<>();

        Queue<DependencyNode> fringe = new LinkedList<>();
        ((LinkedList<DependencyNode>) fringe).add(rootNode);

        while (!fringe.isEmpty()) {

            DependencyNode child = fringe.poll();

            Artifact childArtifact = resolveArtifactJar(child);

            if (!isWinnerNode(child)) continue;

            Set<String> nestedLibraries = getNestedPomDependenciesCoordsForExclusion(childArtifact);

            Set<String> nodeMarkerLibraries = new HashSet<>();

            for (String nestedLibrary : nestedLibraries) {

                if (checkedLibraries.contains(nestedLibrary))
                    continue;

                nodeMarkerLibraries.add(nestedLibrary);
                checkedLibraries.add(nestedLibrary);
            }

            markedLayeredNodes.add(new Pair<>(child, nodeMarkerLibraries));

            if (!child.getChildren().isEmpty())
                ((LinkedList<DependencyNode>) fringe).addAll(child.getChildren());
        }

        return markedLayeredNodes;
    }


    // Loading of marked dependencies
    // Order doesn't matter so we're exploring exhaustively
    // Classes, defined && missed earlier are refused to be loaded later
    private void loadDependencies(List<Pair<DependencyNode, Set<String>>> markedNodes) throws IOException,
            ArtifactResolutionException {

        Map<DependencyNode, Set<String>> nodesWithMissedClasses = new HashMap<>();
        Map<DependencyNode, Set<Class>> nodesWithRecognizedClasses = new HashMap<>();

        while (true) {

            boolean found = false;

            for (Pair<DependencyNode, Set<String>> markedNode : markedNodes) {

                DependencyNode currentNode = markedNode.component1();

                Set<String> filterClassNames = new HashSet<>();

                for (Map.Entry<DependencyNode, Set<String>> pair : nodesWithMissedClasses.entrySet()) {
                    filterClassNames.addAll(pair.getValue());
                }

                Pair<Set<Class>,Set<String>> loadResults = loadArtifact(currentNode, filterClassNames);

                if (((nodesWithMissedClasses.get(currentNode) != null) &&
                        (nodesWithMissedClasses.get(currentNode).size()
                                > loadResults.getSecond().size())) ||
                        (nodesWithMissedClasses.get(currentNode) == null)) {
                    found = true;
                    nodesWithMissedClasses.put(currentNode, loadResults.getSecond());
                    nodesWithRecognizedClasses.put(currentNode, loadResults.getFirst());
                }
            }

            if (!found)
                break;
        }

        logLoadingStatus(markedNodes, nodesWithMissedClasses, nodesWithRecognizedClasses);
    }

    private void logLoadingStatus(List<Pair<DependencyNode, Set<String>>> markedNodes,
                                  Map<DependencyNode, Set<String>> nodesWithMissedClasses,
                                  Map<DependencyNode, Set<Class>> nodesWithRecognizedClasses) {

        for (Pair<DependencyNode, Set<String>> markedNode : markedNodes) {

            System.out.println("");
            System.out.println("~ Archive "+ ArtifactIdUtils.toBaseId(markedNode.getFirst().getArtifact())+" ~");
            System.out.println("Contains libs:");

            for (String nestedLibrary: markedNode.getSecond()){
                System.out.println("+ "+nestedLibrary);
            }

            System.out.println("Classes recognized:");

            for (Class aClass: nodesWithRecognizedClasses.get(markedNode.getFirst())){
                System.out.println("* "+aClass.getCanonicalName());
            }

            System.out.println("Classes missed:");

            for (String className: nodesWithMissedClasses.get(markedNode.getFirst())){
                System.out.println("* "+className);
            }
        }
    }


    private Pair<Set<Class>,Set<String>> loadArtifact(DependencyNode dependencyNode, Set<String> filterClassNames)
            throws ArtifactResolutionException, IOException {

        Artifact artifact = resolveArtifactJar(dependencyNode);

        return customClassLoader.loadLibraryClassSet(artifact.getFile().getPath(),
                filterClassNames); //TODO: check for multimodule proj

    }

    private Artifact resolveArtifactJar(DependencyNode dependencyNode) throws ArtifactResolutionException {

        ArtifactRequest artifactRequest = new ArtifactRequest();

        artifactRequest.setArtifact(dependencyNode.getArtifact());

        artifactRequest.setRepositories(AetherUtils.newRemoteRepositories(repositorySystem, defaultRepositorySystemSession));

        ArtifactResult artifactResult = null;

        artifactResult = repositorySystem.resolveArtifact(defaultRepositorySystemSession, artifactRequest);

        return Objects.requireNonNull(artifactResult).getArtifact();
    }

}
