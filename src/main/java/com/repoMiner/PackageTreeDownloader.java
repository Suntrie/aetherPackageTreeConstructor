package com.repoMiner;

import javafx.util.Pair;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.building.*;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
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

import java.io.File;
import java.io.IOException;
import java.util.*;

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

    private String getUnversionedPackageCoords(Artifact artifact) {                                         // TODO: implement Maven logic for parent pom
        return artifact.getGroupId() + ":" + artifact.getArtifactId();
    }

    Map<DependencyNode, List<String>>
            unwinnedChildNodesForParent=new HashMap<>();

    @Override
    public boolean visitEnter(DependencyNode dependencyNode) {

        // Если нода - невыигравшая, не делаем ничего
        DependencyNode currentNodeWinner=(DependencyNode) dependencyNode.
                getData().get( ConflictResolver.NODE_DATA_WINNER );

        if (currentNodeWinner!=null)
            return true;

        // Если у ноды нет детей, её в любом случае можно загрузить
        if (dependencyNode.getChildren().size() == 0) {
            try {
                loadPackage(dependencyNode, RetrieveType.to);
            } catch (ArtifactDescriptorException | DependencyCollectionException | IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }

        return true;
    }

    @Override
    public boolean visitLeave(DependencyNode dependencyNode) {

        // Если нода - невыигравшая, не делаем ничего
        DependencyNode currentNodeWinner=(DependencyNode) dependencyNode.
                getData().get( ConflictResolver.NODE_DATA_WINNER );

        if (currentNodeWinner!=null)
            return true;

        if (dependencyNode.getChildren().size() != 0) {

            // Добавляем для ноды всех невыигравших детей, загрузки которых будем ждать до загрузки самой ноды
            for (DependencyNode childNode : dependencyNode.getChildren()) {
                //TODO: check: if null for actual winner?

                DependencyNode winner = (DependencyNode) childNode.getData().get(ConflictResolver.NODE_DATA_WINNER);

                if (winner != null) {

                    String unrevisionedDependencyName = getUnversionedPackageCoords(childNode.getArtifact());

                    unwinnedChildNodesForParent.computeIfAbsent(dependencyNode, k -> new ArrayList<>());
                    unwinnedChildNodesForParent.get(dependencyNode).add(unrevisionedDependencyName);
                }
            }

            if (unwinnedChildNodesForParent.get(dependencyNode)==null){
                try {
                    loadPackage(dependencyNode, RetrieveType.from);
                } catch (ArtifactDescriptorException | DependencyCollectionException | IOException | ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }

        return true;
    }

    private enum RetrieveType{
        to,
        from,
        restored
    }

    private void loadPackage(DependencyNode dependencyNode, RetrieveType to) throws ArtifactDescriptorException, DependencyCollectionException, IOException, ClassNotFoundException {

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

        if (dependencyNode.getDependency()==null)
        {
            optionality=false;
        }else{
            optionality=dependencyNode.getDependency().isOptional();
        }

        if (to==RetrieveType.to) {
            System.out.println("Visit package (possible leaf): " + dependencyNode.getArtifact()+" "+
                    (optionality?"d":"n"));
        } else if (to==RetrieveType.from){
            System.out.println("Visit package (branching): " + dependencyNode.getArtifact()+" "+
                    (optionality?"d":"n"));
        } else {
            System.out.println("Visit restored after dirty dependency resolution (branching): " + dependencyNode.getArtifact()+" "+
                    (optionality?"d":"n"));
        }

        // Очищаем список "грязных зависимостей" и загружаем использующие пакеты

        for(Map.Entry<DependencyNode,
                List<String>> pair: unwinnedChildNodesForParent.entrySet()){

            pair.getValue().remove(getUnversionedPackageCoords(artifact));

            if (pair.getValue().size()==0)
                try {
                    unwinnedChildNodesForParent.remove(pair.getKey());
                    DependencyNode key=pair.getKey();
                    loadPackage(key, RetrieveType.restored);
                } catch (ArtifactDescriptorException | DependencyCollectionException | IOException | ClassNotFoundException e) {
                    e.printStackTrace();
                }
        }

        //checkAndResolveParents(artifact, dependencyNode.getChildren());
    }

    private CustomClassLoader customClassLoader = new CustomClassLoader();


    private void checkAndResolveParents(Artifact artifact, List<DependencyNode> children)
            throws ArtifactDescriptorException, DependencyCollectionException, IOException, ClassNotFoundException {

        ModelBuilder builder = null;
        String coords = getUnversionedPackageCoords(artifact);

        try {
            builder = new DefaultPlexusContainer().lookup(ModelBuilder.class);
        } catch (ComponentLookupException | PlexusContainerException e) {
            e.printStackTrace();
        }

        ModelBuildingRequest req = new DefaultModelBuildingRequest();
        req.setProcessPlugins(false);
        req.setModelResolver(new RepoModelResolver(defaultRepositorySystemSession));
        req.setModelSource(new FileModelSource(new File(artifact.getFile().getPath().replace("jar", "pom"))));

        ModelBuildingResult modelBuildingResult = null;

        try {
            assert builder != null;
            modelBuildingResult = builder.build(req);
        } catch (ModelBuildingException e) {
            e.printStackTrace();
        }

        if (modelBuildingResult.getModelIds().size() != 2) {

            List<Dependency> dependencies = modelBuildingResult.getEffectiveModel().getDependencies();

            modelBuildingResult.getEffectiveModel().getModules();

            for (Dependency dependency : dependencies) {
                if (children.stream().noneMatch(it -> it.getDependency().getArtifact().getGroupId().equals(dependency.getGroupId())
                        && it.getDependency().getArtifact().getArtifactId().equals(dependency.getArtifactId())
                        && it.getDependency().getArtifact().getVersion().equals(dependency.getVersion())
                ))
                    if (!(dependency.getScope().equals("test") || dependency.getScope().equals("system")))
                        makeTree(dependency.getGroupId() + ":" + dependency.getArtifactId() + ":" + dependency.getVersion());
            }
        }

        try {
            String jarPath=defaultRepositorySystemSession.getLocalRepository().getBasedir() + "\\" +
                    artifact.getGroupId().replace(".", "\\") + "\\"
                    + artifact.getArtifactId() + "\\" + artifact.getVersion() + "\\" +
                    artifact.getArtifactId() + "-" + artifact.
                    getVersion() + ".jar";
            customClassLoader.loadLibraryClassSet(jarPath);
        } catch (Error e) {
            e.printStackTrace();
        }


    }

    private String getUnversionedPackageCoords(Dependency dependency) {
        return dependency.getGroupId() + ":" + dependency.getArtifactId();
    }

    void makeTree(String coords) throws ArtifactDescriptorException, DependencyCollectionException {

        Artifact artifact = new DefaultArtifact("com.repoMiner.tester:distibution:1.0-SNAPSHOT");

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
