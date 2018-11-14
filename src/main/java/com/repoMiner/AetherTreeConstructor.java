package com.repoMiner;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.resolution.ArtifactDescriptorException;

public class AetherTreeConstructor {

    private static RepositorySystem repositorySystem = AetherUtils.newRepositorySystem();

    private DefaultRepositorySystemSession defaultRepositorySystemSession;

    static {
        PackageTreeDownloader.setRepositorySystem(repositorySystem);
        RepoModelResolver.setRepositorySystem(repositorySystem);
    }

    public AetherTreeConstructor(String localRepositoryDir) {
        this.defaultRepositorySystemSession = AetherUtils.newRepositorySystemSession(repositorySystem, localRepositoryDir);
    }

    public void loadPackageTree(String coords) throws ArtifactDescriptorException, DependencyCollectionException {

        PackageTreeDownloader packageTreeDownloader=new PackageTreeDownloader(defaultRepositorySystemSession);
        packageTreeDownloader.makeTree(coords);
    }



}
