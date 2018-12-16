package com.repoMiner;

import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactResolutionException;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

public class Main {
    public static void main(String[] args) throws ArtifactResolutionException, ArtifactDescriptorException, XmlPullParserException, DependencyCollectionException, IOException {
        AetherTreeConstructor aetherTreeConstructor = new AetherTreeConstructor
                ("C:\\Users\\Neverland\\.m2\\repository");
        Set<Method> methodSet = new HashSet<>();

        Set<String> filters=new HashSet<>();
        filters.add("java.lang");

        aetherTreeConstructor.loadPackageTree(
                "com.repoMiner.tester:" +
                        "providedIgnorer:1.0-SNAPSHOT", filters);

        /*try {
            methodSet = aetherTreeConstructor.loadPackageTree(
                    "com.repoMiner.tester:" +
                            "providedIgnorer:1.0-SNAPSHOT", filters);
        } catch (ArtifactDescriptorException | DependencyCollectionException
                | XmlPullParserException | IOException | ArtifactResolutionException e) {
            e.printStackTrace();
        }

        if (methodSet != null)
            for (Method method : methodSet) {
                System.out.println("~X Class: " + method.getDeclaringClass().getCanonicalName());
                System.out.println("~M Method: "+method.toGenericString());
            }
*/
    }
}
