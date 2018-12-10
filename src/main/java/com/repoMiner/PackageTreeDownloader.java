package com.repoMiner;

import javafx.util.Pair;
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
import org.eclipse.aether.graph.DependencyVisitor;
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

public class PackageTreeDownloader implements DependencyVisitor {

    private static RepositorySystem repositorySystem;
    private final DefaultRepositorySystemSession defaultRepositorySystemSession;
    private String basePackageCoordinates;

    public PackageTreeDownloader(DefaultRepositorySystemSession defaultRepositorySystemSession) {
        this.defaultRepositorySystemSession = defaultRepositorySystemSession;
    }

    public static RepositorySystem getRepositorySystem() {
        return repositorySystem;
    }

    public static void setRepositorySystem(RepositorySystem repositorySystem) {
        PackageTreeDownloader.repositorySystem = repositorySystem;
    }

    Map<DependencyNode, List<String>>
            unwinnedChildNodesForParent = new HashMap<>();

    Set<String> nestedDependencies = new HashSet<>();
    Set<DependencyNode> usersOfNestedDependencies = new HashSet<>();

    @Override
    public boolean visitEnter(DependencyNode dependencyNode) {

        // Если нода - невыигравшая, не делаем ничего
        DependencyNode currentNodeWinner = (DependencyNode) dependencyNode.
                getData().get(ConflictResolver.NODE_DATA_WINNER);

        if (currentNodeWinner != null)
            return true;

        // Если у ноды нет детей, её в любом случае можно загрузить
        if (dependencyNode.getChildren().size() == 0) {
            loadArtifact(dependencyNode, RetrieveType.to);
        }

        return true;
    }

    @Override
    public boolean visitLeave(DependencyNode dependencyNode) {

        // Если нода - невыигравшая, не делаем ничего
        DependencyNode currentNodeWinner = (DependencyNode) dependencyNode.
                getData().get(ConflictResolver.NODE_DATA_WINNER);

        if (currentNodeWinner != null)
            return true;

        if (dependencyNode.getChildren().size() != 0) {

            // Добавляем для ноды всех невыигравших детей, загрузки которых будем ждать до загрузки самой ноды
            for (DependencyNode childNode : dependencyNode.getChildren()) {

                DependencyNode winner = (DependencyNode) childNode.getData().get(ConflictResolver.NODE_DATA_WINNER);

                if (winner != null) {

                    String unrevisionedDependencyName =
                            ArtifactIdUtils.toVersionlessId(childNode.getArtifact());

                    unwinnedChildNodesForParent.computeIfAbsent(dependencyNode, k -> new ArrayList<>());
                    unwinnedChildNodesForParent.get(dependencyNode).add(unrevisionedDependencyName);
                }
            }

            if (unwinnedChildNodesForParent.get(dependencyNode) == null) {
                loadArtifact(dependencyNode, RetrieveType.from);
            }
        }

        return true;
    }

    public void setBasePackageCoordinates(String basePackageCoordinates) {
        this.basePackageCoordinates = basePackageCoordinates;
    }

    private enum RetrieveType {
        to,
        from,
        restored
    }


    private void loadArtifactClasses(RetrieveType retrieveType,
                                     Artifact artifact,
                                     boolean optionality,
                                     boolean rootDependency) {

        if (rootDependency)
            System.out.println("__Root dependency__");

        System.out.print("Visit artifact: " + artifact + " " +
                (optionality ? "optional" : "non-optional") + ",");

        switch (retrieveType) {
            case to:
                System.out.println(" leaf");
                break;
            case from:
                System.out.println(" branching");
                break;
            case restored:
                System.out.println(" branching (restored)");
                break;
        }

        String jarPath = artifact.getFile().getPath();

        try {
            customClassLoader.getExecutableLibraryMethods(jarPath).getFirst();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if ((rootDependency) && (basePackageCoordinates.equals(ArtifactIdUtils.toBaseId(artifact))))
            try {
                for (String present : customClassLoader.getExecutableLibraryMethods(jarPath).getSecond().stream().map(Class::getName).collect(Collectors.toSet()))
                    System.out.println("Class present: " + present);
                for (String missed : customClassLoader.getMissedClassNames(jarPath))
                    System.out.println("Class missed: " + missed);
                for (String missed : customClassLoader.getExecutableLibraryMethods(jarPath).getThird().stream().map(Class::getName).collect(Collectors.toSet()))
                    System.out.println("Class missed (methods): " + missed);

            } catch (IOException e) {
                e.printStackTrace();
            }

    }

    private Pair<Artifact, Boolean> resolveArtifactJar(DependencyNode dependencyNode) {

        ArtifactRequest artifactRequest = new ArtifactRequest();

        artifactRequest.setArtifact(dependencyNode.getArtifact());

        artifactRequest.setRepositories(AetherUtils.newRemoteRepositories(repositorySystem, defaultRepositorySystemSession));

        ArtifactResult artifactResult = null;

        try {
            artifactResult = repositorySystem.resolveArtifact(defaultRepositorySystemSession, artifactRequest); //TODO: для чего у нас используется resolve?

        } catch (ArtifactResolutionException e) {
            e.printStackTrace();
        }

        Artifact artifact = Objects.requireNonNull(artifactResult).getArtifact();

/*
        // получение вложенных зависимостей (их мы загружать до загрузки внешних не будем)
        nestedDependencies = getNestedPomDependenciesCoordsForExclusion(descriptorResult.getArtifact());*/

        boolean optionality;

        if (dependencyNode.getDependency() == null) {
            optionality = false; // Пришли с корневой нодой
        } else {
            optionality = dependencyNode.getDependency().isOptional();
        }

        return new Pair<>(artifact, optionality);
    }

    private void loadArtifact(DependencyNode dependencyNode, RetrieveType to) {

        boolean rootDependency = dependencyNode.getDependency() == null;

        // Не загружаем зависимости, которые есть внутри jar-а

        if (nestedDependencies.contains(ArtifactIdUtils.toVersionlessId(dependencyNode.getArtifact()))) {
            return;
        }

        Pair<Artifact, Boolean> loadedJarDescription = resolveArtifactJar(dependencyNode);

        Artifact artifact = loadedJarDescription.getKey();
        Boolean optionality = loadedJarDescription.getValue();

        loadArtifactClasses(to, artifact, optionality, rootDependency); //TODO: check for multimodule proj

        // Очищаем список "грязных зависимостей" и загружаем использующие пакеты

        for (Map.Entry<DependencyNode,
                List<String>> pair : unwinnedChildNodesForParent.entrySet()) {

            pair.getValue().remove(ArtifactIdUtils.toVersionlessId(artifact));

            if (pair.getValue().size() == 0) {
                unwinnedChildNodesForParent.remove(pair.getKey());
                DependencyNode key = pair.getKey();
                loadArtifact(key, RetrieveType.restored);
            }
        }
    }

    private Set<String> getNestedPomDependenciesCoordsForExclusion(Artifact artifact) throws IOException, XmlPullParserException {

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

            Model model = reader.read(jarInputStream);   //TODO: check

            String coords = model.getGroupId() + ":" + model.getArtifactId();

            dependencies.add(coords);
        }

        return dependencies;
    }

    private CustomClassLoader customClassLoader = new CustomClassLoader();

    void makeTree(String coords, boolean start) throws ArtifactDescriptorException, DependencyCollectionException, IOException, XmlPullParserException {

        Artifact artifact = new DefaultArtifact(coords);

        AetherUtils.setFiltering(defaultRepositorySystemSession, !start);

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

        collectResult.getRoot().accept(this);
    }
}
