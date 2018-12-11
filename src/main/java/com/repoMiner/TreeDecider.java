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

        // вычисление нод-победителей для пересекающихся множеств вложенных библиотек
        Map<String, DependencyNode> libraryToNodeResolutionMap = new HashMap<>();

        checkForIntersectionWin(libraryToNodeResolutionMap, rootNode);
        calculateIntersectionWinnerDependencies(rootNode, libraryToNodeResolutionMap);

        loadDependencies(getLayeredWinnerNodes(rootNode), libraryToNodeResolutionMap);
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

    private List<DependencyNode> getLayeredWinnerNodes(DependencyNode rootNode) {

        List<DependencyNode> layeredWinnerNodes = new ArrayList<>();

        Queue<DependencyNode> fringe = new LinkedList<>();
        fringe.add(rootNode);

        while (!fringe.isEmpty()) {
            DependencyNode child = fringe.poll();

            if (!isWinnerNode(child)) continue;

            layeredWinnerNodes.add(child);

            if (!child.getChildren().isEmpty())
                fringe.addAll(child.getChildren());
        }
        return layeredWinnerNodes;
    }

    // Depth in first, tested

    private void calculateIntersectionWinnerDependencies(DependencyNode rootNode,
                                                         Map<String, DependencyNode> libraryToNodeResolutionMap)
            throws ArtifactResolutionException, IOException, XmlPullParserException {

        for (DependencyNode child : rootNode.getChildren()) {

            if (!isWinnerNode(child)) continue;

            checkForIntersectionWin(libraryToNodeResolutionMap, child);

            calculateIntersectionWinnerDependencies(child, libraryToNodeResolutionMap);
        }
    }

    private void checkForIntersectionWin(Map<String, DependencyNode> libraryToNodeResolutionMap, DependencyNode child) throws ArtifactResolutionException, IOException, XmlPullParserException {
        Artifact childArtifact = resolveArtifactJar(child);

        Set<String> nestedLibraries = getNestedPomDependenciesCoordsForExclusion(childArtifact);

        for (String nestedLibrary : nestedLibraries) {
            if (!libraryToNodeResolutionMap.containsKey(nestedLibrary))
                libraryToNodeResolutionMap.put(nestedLibrary, child);
        }
    }


    // Loading of marked dependencies
    // Order doesn't matter so we're exploring exhaustively
    // Classes, defined && missed earlier are refused to be loaded later

    private void loadDependencies(List<DependencyNode> layeredWinnerNodes,
                                  Map<String, DependencyNode> libraryToNodeResolutionMap)
            throws IOException, XmlPullParserException, ArtifactResolutionException {

        Map<DependencyNode, Set<String>> nodesWithMissedClasses = new HashMap<>();
        Map<DependencyNode, Set<Class>> nodesWithRecognizedClasses = new HashMap<>();

        Map<DependencyNode, Set<String>> markedNodes = new HashMap<>();

        while (true) {

            boolean found = false;

            for (DependencyNode currentNode : layeredWinnerNodes) {

                Set<String> filterClassNames = new HashSet<>();

                Artifact currentArtifact = resolveArtifactJar(currentNode);

                Set<String> nestedLibraries = getNestedPomDependenciesCoordsForExclusion(currentArtifact);

                boolean isRealWinner = false;

                for (String nestedLibrary : nestedLibraries) {
                    if (libraryToNodeResolutionMap.containsKey(nestedLibrary) &&
                            !libraryToNodeResolutionMap.get(nestedLibrary).equals(currentNode)) {
                        filterClassNames.addAll(getNodesIntersection(currentNode,
                                libraryToNodeResolutionMap.get(nestedLibrary)));
                    } else {

                        Set<String> currentSet = markedNodes.getOrDefault(currentNode, new HashSet<>());
                        currentSet.add(nestedLibrary);
                        markedNodes.put(currentNode, currentSet);
                        isRealWinner = true;
                    }
                }

                if (!isRealWinner) {
                    markedNodes.put(currentNode, new HashSet<>());
                    continue;
                }

                Pair<Set<Class>, Set<String>> loadResults = loadArtifact(currentNode, filterClassNames);

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

    private Set<String> getAllArtifactsClassNames(Artifact artifact) throws IOException {

        JarFile jar = new JarFile(artifact.getFile());
        JarEntry jarEntry;

        Enumeration<JarEntry> jarEntryEnumeration = jar.entries();

        Set<String> classNames = new HashSet<>();

        while (true) {

            jarEntry = getNextJarEntryMatches(jarEntryEnumeration, ".class");       //filter target

            if (jarEntry == null) {
                break;
            }

            classNames.add(jarEntry.getName());
        }

        return classNames;
    }

    private Set<String> getNodesIntersection(DependencyNode currentNode, DependencyNode winnerNode)
            throws ArtifactResolutionException, IOException {

        Artifact currentArtifact = resolveArtifactJar(currentNode);
        Artifact winnerArtifact = resolveArtifactJar(winnerNode);

        Set<String> classNames = getAllArtifactsClassNames(currentArtifact);
        classNames.retainAll(getAllArtifactsClassNames(winnerArtifact));

        return classNames;

    }

    private void logLoadingStatus(Map<DependencyNode, Set<String>> markedNodes,
                                  Map<DependencyNode, Set<String>> nodesWithMissedClasses,
                                  Map<DependencyNode, Set<Class>> nodesWithRecognizedClasses) {

        for (Map.Entry<DependencyNode, Set<String>> markedNode : markedNodes.entrySet()) {

            System.out.println();
            System.out.println("~ Archive " + ArtifactIdUtils.toBaseId(markedNode.getKey().getArtifact()) + " ~");

            if (markedNode.getValue().isEmpty()) continue;

            System.out.println("Contains libs:");

            for (String nestedLibrary : markedNode.getValue()) {
                System.out.println("+ " + nestedLibrary);
            }


            if (!nodesWithRecognizedClasses.get(markedNode.getKey()).isEmpty())
            System.out.println("Classes recognized:");

            for (Class aClass : nodesWithRecognizedClasses.get(markedNode.getKey())) {
                System.out.println("* " + aClass.getCanonicalName());
            }


            if (nodesWithMissedClasses.get(markedNode.getKey()).isEmpty())
            System.out.println("Classes missed:");

            for (String className : nodesWithMissedClasses.get(markedNode.getKey())) {
                System.out.println("* " + className);
            }
        }
    }


    private Pair<Set<Class>, Set<String>> loadArtifact(DependencyNode dependencyNode, Set<String> filterClassNames)
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
