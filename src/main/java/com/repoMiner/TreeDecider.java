package com.repoMiner;

import kotlin.Pair;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
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
import java.lang.reflect.Method;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static com.repoMiner.CustomClassLoader.getNextJarEntryMatches;

public class TreeDecider {

    private static RepositorySystem repositorySystem;
    private DefaultRepositorySystemSession defaultRepositorySystemSession;

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

    private DependencyNode getDependencyTree(String coords) throws ArtifactDescriptorException, DependencyCollectionException {
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

        return collectResult.getRoot();
    }

    private DependencyNodeDescriptorMap getWinnerPackagesClassification(String coords, Set<String> filterPatterns)
            throws XmlPullParserException,
            IOException, ArtifactResolutionException, ArtifactDescriptorException, DependencyCollectionException {

        DependencyNode rootNode=getDependencyTree(coords);
        // вычисление нод-победителей для пересекающихся множеств вложенных библиотек
        Map<String, DependencyNode> libraryToNodeResolutionMap = new HashMap<>();

        checkForIntersectionWin(libraryToNodeResolutionMap, rootNode);
        calculateIntersectionWinnerDependencies(rootNode, libraryToNodeResolutionMap);

        DependencyNodeDescriptorMap dependencyNodeDescriptorMap = new DependencyNodeDescriptorMap();

        loadNodesExhaustively(getLayeredWinnerNodes(rootNode),
                libraryToNodeResolutionMap,
                dependencyNodeDescriptorMap);

        filterCommonClasses(dependencyNodeDescriptorMap, filterPatterns);

        return dependencyNodeDescriptorMap;
    }

    private void filterCommonClasses(DependencyNodeDescriptorMap dependencyNodeDescriptorMap, Set<String> filterPatterns) {

        for (Pair<DependencyNode, Set<Class>> pair : dependencyNodeDescriptorMap.getDependencyNodesWithFoundClasses()) {
            for (String filter : filterPatterns) {
                pair.getSecond().removeIf(it -> it.getCanonicalName().contains(filter));
            }
        }
    }
    // Function to get the whole graph for the library with coords

    public void logWinnerLibsAndClasses(String coords, Set<String> filterPatterns) throws ArtifactDescriptorException, DependencyCollectionException,
            IOException, XmlPullParserException, ArtifactResolutionException {

        DependencyNodeDescriptorMap winnersClassificaiton = getWinnerPackagesClassification(coords,
                filterPatterns);
        logLoadingStatus(winnersClassificaiton);
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

            String groupId = model.getGroupId();

            while (groupId == null) {

                Parent parent = model.getParent();

                if (parent == null) break;

                groupId = parent.getGroupId();
            }

            String coords = groupId + ":" + model.getArtifactId();

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

    private void checkForIntersectionWin(Map<String, DependencyNode> libraryToNodeResolutionMap, DependencyNode child)
            throws ArtifactResolutionException, IOException, XmlPullParserException {
        Artifact childArtifact = resolveArtifactJar(child);

        Set<String> nestedLibraries = getNestedPomDependenciesCoordsForExclusion(childArtifact);

        for (String nestedLibrary : nestedLibraries) {
            if (!libraryToNodeResolutionMap.containsKey(nestedLibrary))
                libraryToNodeResolutionMap.put(nestedLibrary, child);
        }
    }


    public Set<Method> getAPIMethods(String coords, Set<String> filterPatterns, boolean root) throws ArtifactDescriptorException, XmlPullParserException,
            DependencyCollectionException, ArtifactResolutionException, IOException {

        DependencyNodeDescriptorMap winnersClassificaiton = getWinnerPackagesClassification(coords, filterPatterns);

        Set<Method> result = new HashSet<>();

            for (Pair<DependencyNode, Set<String>> markedNode : winnersClassificaiton.
                    getDependencyNodesWithNestedLibs()) {

                if (root&&! ArtifactIdUtils.toBaseId(markedNode.getFirst().getArtifact())
                        .equals(ArtifactIdUtils.toBaseId(getDependencyTree(coords).getArtifact()))) continue;

                if (markedNode.getSecond().isEmpty() || winnersClassificaiton.
                        getDependencyNodeDescriptor(markedNode.getFirst()).getFoundClasses().size() == 0) continue;

                for (Class aClass : winnersClassificaiton.getDependencyNodeDescriptor(markedNode.getFirst()).getFoundClasses()) {

                    if (customClassLoader.getClassExecutablePublicMethods(aClass) != null) {
                        for (Method method : customClassLoader.getClassExecutablePublicMethods(aClass)) {
                            boolean toFilter = false;
                            for (String filter : filterPatterns) {
                                if (method.getDeclaringClass().getCanonicalName().contains(filter)) {
                                    toFilter = true;
                                    break;
                                }
                            }
                            if (!toFilter)
                                result.add(method);
                        }

                    }
                }
            }

        return result;
    }

    private void loadNodesExhaustively
            (List<DependencyNode> layeredWinnerNodes,
             Map<String, DependencyNode> libraryToNodeResolutionMap,
             DependencyNodeDescriptorMap dependencyNodeDescriptorMap) throws ArtifactResolutionException, IOException,
            XmlPullParserException {

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

                if ((!isRealWinner) && (filterClassNames.size() != getAllArtifactsClassNames(currentArtifact).size())) {
                    isRealWinner = true;
                    Set<String> intersectionForUnwinner = new HashSet<>();
                    intersectionForUnwinner.add("intersection");
                    markedNodes.put(currentNode, intersectionForUnwinner);
                }

                if (!isRealWinner) {
                    markedNodes.put(currentNode, new HashSet<>());
                    continue;
                }

                Pair<Set<Class>, Set<String>> loadResults = loadArtifact(currentNode, filterClassNames);

                Set<Class> foundClasses = dependencyNodeDescriptorMap.
                        getDependencyNodeDescriptor(currentNode).getFoundClasses();

                if ((foundClasses.size() < loadResults.getFirst().size())) {
                    found = true;
                    dependencyNodeDescriptorMap.putDependencyNode(currentNode,
                            new DependencyNodeDescriptorMap.DependencyNodeDescriptor(loadResults.getFirst(),
                                    loadResults.getSecond(), markedNodes.get(currentNode)));
                }
            }

            if (!found)
                break;
        }

        return;
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

    private void logLoadingStatus(DependencyNodeDescriptorMap dependencyNodeDescriptorMap) {

        for (Pair<DependencyNode, Set<String>> markedNode : dependencyNodeDescriptorMap.
                getDependencyNodesWithNestedLibs()) {

            System.out.println();
            System.out.println("~ Archive " + ArtifactIdUtils.toBaseId(markedNode.getFirst().getArtifact()) + " ~");

            if (markedNode.getSecond().isEmpty()) continue;

            System.out.println("Contains libs:");

            for (String nestedLibrary : markedNode.getSecond()) {
                System.out.println("+ " + nestedLibrary);
            }


            if (!dependencyNodeDescriptorMap.getDependencyNodeDescriptor(markedNode.getFirst()).
                    getFoundClasses().isEmpty())
                System.out.println("Classes recognized:");

            for (Class aClass : dependencyNodeDescriptorMap.getDependencyNodeDescriptor(markedNode.getFirst()).
                    getFoundClasses()) {
                System.out.println("* " + aClass.getCanonicalName());
            }


            if (!dependencyNodeDescriptorMap.getDependencyNodeDescriptor(markedNode.getFirst()).
                    getMissedClasses().isEmpty())
                System.out.println("Classes missed:");

            for (String className : dependencyNodeDescriptorMap.getDependencyNodeDescriptor(markedNode.getFirst()).
                    getMissedClasses()) {
                System.out.println("* " + className);
            }
        }
    }


    private Pair<Set<Class>, Set<String>> loadArtifact(DependencyNode dependencyNode, Set<String> filterClassNames)
            throws ArtifactResolutionException, IOException {

        Artifact artifact = resolveArtifactJar(dependencyNode);

        return customClassLoader.loadLibraryClassSet(artifact.getFile().getPath(),
                filterClassNames);

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
