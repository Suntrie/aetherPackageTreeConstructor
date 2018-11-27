package com.repoMiner;

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
import org.eclipse.aether.util.graph.transformer.ConflictResolver;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class PackageTreeDownloader implements DependencyVisitor {

    private static RepositorySystem repositorySystem;
    private final DefaultRepositorySystemSession defaultRepositorySystemSession;
    private static List<String> coordsList = new ArrayList<>();

    public PackageTreeDownloader(DefaultRepositorySystemSession defaultRepositorySystemSession) {
        this.defaultRepositorySystemSession = defaultRepositorySystemSession;
    }

    public static RepositorySystem getRepositorySystem() {
        return repositorySystem;
    }

    public static void setRepositorySystem(RepositorySystem repositorySystem) {
        PackageTreeDownloader.repositorySystem = repositorySystem;
    }

    private String getUnversionedPackageCoords(Artifact artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId();
    }

    Map<DependencyNode, List<String>>
            unwinnedChildNodesForParent = new HashMap<>();

    @Override
    public boolean visitEnter(DependencyNode dependencyNode) {

        // Если нода - невыигравшая, не делаем ничего
        DependencyNode currentNodeWinner = (DependencyNode) dependencyNode.
                getData().get(ConflictResolver.NODE_DATA_WINNER);

        if (currentNodeWinner != null)
            return true;

        // Если у ноды нет детей, её в любом случае можно загрузить
        if (dependencyNode.getChildren().size() == 0) {
            loadPackage(dependencyNode, RetrieveType.to);
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

                    String unrevisionedDependencyName = getUnversionedPackageCoords(childNode.getArtifact());

                    unwinnedChildNodesForParent.computeIfAbsent(dependencyNode, k -> new ArrayList<>());
                    unwinnedChildNodesForParent.get(dependencyNode).add(unrevisionedDependencyName);
                }
            }

            if (unwinnedChildNodesForParent.get(dependencyNode) == null) {
                loadPackage(dependencyNode, RetrieveType.from);
            }
        }

        return true;
    }

    private enum RetrieveType {
        to,
        from,
        restored
    }


    private void doLoadPackage(RetrieveType retrieveType,
                               Artifact artifact,
                               boolean optionality,
                               boolean rootDependency) {

        System.out.print("Visit package: " + artifact + " " +
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

        if (rootDependency)
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

    private void loadPackage(DependencyNode dependencyNode, RetrieveType to) {

        ArtifactRequest artifactRequest = new ArtifactRequest();

        artifactRequest.setArtifact(dependencyNode.getArtifact());

        artifactRequest.setRepositories(AetherUtils.newRemoteRepositories(repositorySystem, defaultRepositorySystemSession));

        ArtifactResult artifactResult = null;

        try {
            artifactResult = repositorySystem.resolveArtifact(defaultRepositorySystemSession, artifactRequest);

        } catch (ArtifactResolutionException e) {
            e.printStackTrace();
        }

        Artifact artifact = Objects.requireNonNull(artifactResult).getArtifact();

        boolean optionality;

        if (dependencyNode.getDependency() == null) {
            optionality = false;
        } else {
            optionality = dependencyNode.getDependency().isOptional();
        }

        doLoadPackage(to, artifact, optionality,dependencyNode.getDependency() == null); //TODO: check for multimodule proj

        // Очищаем список "грязных зависимостей" и загружаем использующие пакеты

        for (Map.Entry<DependencyNode,
                List<String>> pair : unwinnedChildNodesForParent.entrySet()) {

            pair.getValue().remove(getUnversionedPackageCoords(artifact));

            if (pair.getValue().size() == 0) {
                unwinnedChildNodesForParent.remove(pair.getKey());
                DependencyNode key = pair.getKey();
                loadPackage(key, RetrieveType.restored);
            }
        }
    }

    private CustomClassLoader customClassLoader = new CustomClassLoader();


    void makeTree(String coords) throws ArtifactDescriptorException, DependencyCollectionException {

        Artifact artifact = new DefaultArtifact("com.rabbitmq:amqp-client:jar:5.4.3");

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

        collectResult.getRoot().accept(this);
    }
}
