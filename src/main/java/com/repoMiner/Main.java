package com.repoMiner;

import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.resolution.ArtifactDescriptorException;

public class Main {
    public static void main(String[] args) {
        AetherTreeConstructor aetherTreeConstructor=new AetherTreeConstructor
                ("C:\\Users\\Neverland\\.m2\\repository");
        try {
            aetherTreeConstructor.loadPackageTree("com.rabbitmq:amqp-client:jar:5.4.3");
        } catch (ArtifactDescriptorException | DependencyCollectionException e) {
            e.printStackTrace();
        }
    }
}
