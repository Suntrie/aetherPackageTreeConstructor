package com.repoMiner;

import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.resolution.ArtifactDescriptorException;

import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        AetherTreeConstructor aetherTreeConstructor=new AetherTreeConstructor
                ("C:\\Users\\Neverland\\.m2\\repository");
        try {
            aetherTreeConstructor.loadPackageTree("com.repoMiner.fatInOutExploration:anotherFatJarWithDependentTreeModules:1.0-SNAPSHOT");
        } catch (ArtifactDescriptorException | DependencyCollectionException | XmlPullParserException | IOException e) {
            e.printStackTrace();
        }
    }
}
