package com.repoMiner;

import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import sun.reflect.generics.tree.Tree;

import java.io.IOException;

public class AetherTreeConstructor {

    private static RepositorySystem repositorySystem = AetherUtils.newRepositorySystem();

    private DefaultRepositorySystemSession defaultRepositorySystemSession;

    static {
        //PackageTreeDownloader.setRepositorySystem(repositorySystem);
        TreeDecider.setRepositorySystem(repositorySystem);
        RepoModelResolver.setRepositorySystem(repositorySystem);
    }

    public AetherTreeConstructor(String localRepositoryDir) {
        this.defaultRepositorySystemSession = AetherUtils.newRepositorySystemSession(repositorySystem, localRepositoryDir);
    }

    public void loadPackageTree(String coords) throws ArtifactDescriptorException, DependencyCollectionException,
            IOException, XmlPullParserException, ArtifactResolutionException {

        /*PackageTreeDownloader packageTreeDownloader=new PackageTreeDownloader(defaultRepositorySystemSession);
        packageTreeDownloader.setBasePackageCoordinates(coords);
        packageTreeDownloader.makeTree(coords, true);*/

        TreeDecider treeDecider=new TreeDecider(defaultRepositorySystemSession);
        treeDecider.makeTree(coords);
    }



}
