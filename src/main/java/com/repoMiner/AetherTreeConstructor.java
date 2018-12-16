package com.repoMiner;

import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactResolutionException;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Set;

public class AetherTreeConstructor {

    private static RepositorySystem repositorySystem = AetherUtils.newRepositorySystem();

    private DefaultRepositorySystemSession defaultRepositorySystemSession;

    private TreeDecider treeDecider;

    static {
        TreeDecider.setRepositorySystem(repositorySystem);
    }

    public AetherTreeConstructor(String localRepositoryDir) {
        this.defaultRepositorySystemSession = AetherUtils.newRepositorySystemSession(repositorySystem, localRepositoryDir);
        this.treeDecider=new TreeDecider(defaultRepositorySystemSession);
    }

    public Set<Method> getPackageMethods(String coords, Set<String> filterPatterns)
            throws ArtifactDescriptorException, XmlPullParserException, IOException,
            DependencyCollectionException, ArtifactResolutionException {
        return treeDecider.getAPIMethods(coords, filterPatterns, true);
    }

    public void loadPackageTree(String coords, Set<String> filterPatterns) throws ArtifactDescriptorException, DependencyCollectionException,
            IOException, XmlPullParserException, ArtifactResolutionException {
       treeDecider.logWinnerLibsAndClasses(coords, filterPatterns);
    }

}
