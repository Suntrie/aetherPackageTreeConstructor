package com.repoMiner;

import kotlin.Pair;
import org.eclipse.aether.graph.DependencyNode;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DependencyNodeDescriptorMap {

    public Map<DependencyNode, DependencyNodeDescriptor> getDependencyNodeDependencyNodeDescriptorMap() {
        return dependencyNodeDependencyNodeDescriptorMap;
    }

    private Map<DependencyNode, DependencyNodeDescriptor> dependencyNodeDependencyNodeDescriptorMap= new HashMap<>();

    public void putDependencyNode(DependencyNode dependencyNode, DependencyNodeDescriptor dependencyNodeDescriptor) {
        dependencyNodeDependencyNodeDescriptorMap.put(dependencyNode,
                new DependencyNodeDescriptor(dependencyNodeDescriptor.getFoundClasses(),
                        dependencyNodeDescriptor.getMissedClasses(), dependencyNodeDescriptor.getNestedLibraries()));
    }

    public DependencyNodeDescriptor getDependencyNodeDescriptor(DependencyNode dependencyNode) {
        return dependencyNodeDependencyNodeDescriptorMap.getOrDefault(dependencyNode, new DependencyNodeDescriptor(null,null,null));
    }

    public static class DependencyNodeDescriptor{

        private Set<Class> foundClasses;
        private Set<String> missedClasses;
        private Set<String> nestedLibraries;

        public DependencyNodeDescriptor(Set<Class> foundClasses,
                                        Set<String> missedClasses, Set<String> nestedLibraries){
            this.foundClasses = foundClasses;
            this.missedClasses = missedClasses;

            this.nestedLibraries = nestedLibraries;
        }

        public Set<Class> getFoundClasses() {
            return foundClasses;
        }

        public Set<String> getMissedClasses() {
            return missedClasses;
        }

        public Set<String> getNestedLibraries() {
            return nestedLibraries;
        }
    }


    @NotNull
    public Set<Pair<DependencyNode, Set<String>>> getDependencyNodesWithNestedLibs() {
        return this.getDependencyNodeDependencyNodeDescriptorMap()
                .entrySet().stream().map(it-> new Pair<DependencyNode, Set<String>>
                        (it.getKey(), it.getValue().getNestedLibraries())).collect(Collectors.toSet());
    }

    @NotNull
    public Set<Pair<DependencyNode, Set<String>>> getDependencyNodesWithMissedClasses() {
        return this.getDependencyNodeDependencyNodeDescriptorMap()
                .entrySet().stream().map(it-> new Pair<DependencyNode, Set<String>>(it.getKey(), it.getValue().getMissedClasses())).collect(Collectors.toSet());
    }

    @NotNull
    public Set<Pair> getDependencyNodesWithFoundClasses() {
        return this.getDependencyNodeDependencyNodeDescriptorMap()
                .entrySet().stream().map(it-> new Pair<DependencyNode, Set<Class>>
                        (it.getKey(), it.getValue().getFoundClasses())).collect(Collectors.toSet());
    }

}
