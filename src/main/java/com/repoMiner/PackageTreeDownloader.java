package com.repoMiner;

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
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    @Override
    public boolean visitEnter(DependencyNode dependencyNode) {

        if (dependencyNode.getChildren().size() == 0) {

            String currentCoords = getUnversionedPackageCoords(dependencyNode.getArtifact());

            // if (!coordsList.contains(currentCoords)) {
            coordsList.add(currentCoords);
            try {
                loadPackage(dependencyNode, true);
            } catch (ArtifactDescriptorException | DependencyCollectionException | IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
            //  }
        }

        return true;
    }

    @Override
    public boolean visitLeave(DependencyNode dependencyNode) {

        if (dependencyNode.getChildren().size() != 0) {

            String currentCoords = getUnversionedPackageCoords(dependencyNode.getArtifact());

            //          if (!coordsList.contains(currentCoords)) {
            coordsList.add(currentCoords);
            try {
                loadPackage(dependencyNode, false);
            } catch (ArtifactDescriptorException | DependencyCollectionException | IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
            // }
        }

        return true;
    }

    private void loadPackage(DependencyNode dependencyNode, boolean to) throws ArtifactDescriptorException, DependencyCollectionException, IOException, ClassNotFoundException {

        ArtifactRequest artifactRequest = new ArtifactRequest();

        artifactRequest.setArtifact(dependencyNode.getArtifact());

        artifactRequest.setRepositories(AetherUtils.newRepositories(repositorySystem, defaultRepositorySystemSession));

        ArtifactResult artifactResult = null;
        try {
            artifactResult = repositorySystem.resolveArtifact(defaultRepositorySystemSession, artifactRequest);

        } catch (ArtifactResolutionException e) {
            e.printStackTrace();
        }

        Artifact artifact = Objects.requireNonNull(artifactResult).getArtifact();

        if (to) {
            System.out.println("Visit package (possible leaf): " + dependencyNode.getArtifact());
        } else {

            System.out.println("Visit package (branching): " + dependencyNode.getArtifact());
        }

        checkAndResolveParents(artifact);
    }

    private void checkAndResolveParents(Artifact artifact) throws ArtifactDescriptorException, DependencyCollectionException, IOException, ClassNotFoundException {

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

            for (Dependency dependency : dependencies) {
                // if (!coordsList.contains(getUnversionedPackageCoords(dependency))) {
                if (!(dependency.getScope().equals("test") || dependency.getScope().equals("system")))
                    makeTree(dependency.getGroupId() + ":" + dependency.getArtifactId() + ":" + dependency.getVersion());
                // }
            }
        }

        CustomClassLoader customClassLoader = new CustomClassLoader();

        try {
            customClassLoader.loadLibraryClassSet(defaultRepositorySystemSession.getLocalRepository().getBasedir() + "\\" +
                    artifact.getGroupId().replace(".", "\\") + "\\"
                    + artifact.getArtifactId() + "\\" + artifact.getVersion() + "\\" +
                    artifact.getArtifactId() + "-" + artifact.
                    getVersion() + ".jar");
        } catch (Error e) {
            e.printStackTrace();
        }


    }

    private String getUnversionedPackageCoords(Dependency dependency) {
        return dependency.getGroupId() + ":" + dependency.getArtifactId();
    }

    void makeTree(String coords) throws ArtifactDescriptorException, DependencyCollectionException {

        Artifact artifact = new DefaultArtifact(coords);

        ArtifactDescriptorRequest descriptorRequest = new ArtifactDescriptorRequest();
        descriptorRequest.setArtifact(artifact);
        descriptorRequest.setRepositories(AetherUtils.newRepositories(repositorySystem, defaultRepositorySystemSession));

        ArtifactDescriptorResult descriptorResult = repositorySystem.readArtifactDescriptor(defaultRepositorySystemSession, descriptorRequest);

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRootArtifact(descriptorResult.getArtifact());
        collectRequest.setDependencies(descriptorResult.getDependencies());
        collectRequest.setManagedDependencies(descriptorResult.getManagedDependencies());
        collectRequest.setRepositories(descriptorRequest.getRepositories());

        CollectResult collectResult = repositorySystem.collectDependencies(defaultRepositorySystemSession, collectRequest);

        collectResult.getRoot().accept(this);
    }
}
