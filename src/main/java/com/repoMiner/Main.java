package com.repoMiner;

import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.resolution.ArtifactDescriptorException;

public class Main {
    public static void main(String[] args) {
        AetherTreeConstructor aetherTreeConstructor=new AetherTreeConstructor("C:\\Users\\Neverland\\.m2\\repository");
        try {
            aetherTreeConstructor.loadPackageTree("net.sf.ehcache:ehcache:2.10.4");
        } catch (ArtifactDescriptorException | DependencyCollectionException e) {
            e.printStackTrace();
        }
    }
}
