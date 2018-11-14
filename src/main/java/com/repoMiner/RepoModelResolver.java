package com.repoMiner;

import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.metadata.DefaultMetadata;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.MetadataRequest;
import org.eclipse.aether.resolution.MetadataResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.repoMiner.AetherUtils.newCentralRepository;

public class RepoModelResolver implements ModelResolver
{
    private List<RemoteRepository> repos;
    private static RepositorySystem repositorySystem;
    private RepositorySystemSession repositorySystemSession;

    RepoModelResolver(RepositorySystemSession repositorySystemSession)
    {
        this.repos = new ArrayList<>();
        this.repositorySystemSession=repositorySystemSession;
    }

    private static Metadata getMetadata(RepositorySystem system, RepositorySystemSession session, Artifact artifact)
    {
        Metadata metadata = new DefaultMetadata(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion(),
                artifact.getArtifactId() + "-" + artifact.getBaseVersion() + ".pom",
                Metadata.Nature.RELEASE_OR_SNAPSHOT);
        MetadataRequest request = new MetadataRequest(metadata, newCentralRepository(), null);
        MetadataResult result = system.resolveMetadata(session, Collections.singleton(request)).get(0);
        return result.getMetadata();
    }

    public static RepositorySystem getRepositorySystem() {
        return repositorySystem;
    }

    public static void setRepositorySystem(RepositorySystem repositorySystem) {
        RepoModelResolver.repositorySystem = repositorySystem;
    }

    @Override
    public ModelSource resolveModel(String groupId, String artifactId, String version) throws UnresolvableModelException
    {
        Artifact artifact = new DefaultArtifact(groupId + ":" + artifactId + ":" + version);
        return new FileModelSource(getMetadata(repositorySystem, repositorySystemSession, artifact).getFile());
    }

    @Override
    public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
        return resolveModel(parent.getGroupId(),parent.getArtifactId(),parent.getVersion());
    }

    @Override
    public void addRepository(Repository repository) throws InvalidRepositoryException
    {
        RemoteRepository remote = new RemoteRepository.Builder(repository.getId(), repository.getLayout(), repository.getUrl()).build();
        repos.add(remote);
    }

    @Override
    public void addRepository(Repository repository, boolean b) throws InvalidRepositoryException {
       addRepository(repository);
    }

    @Override
    public ModelResolver newCopy()
    {
        return this;
    }

}