package com.repoMiner;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.collection.DependencySelector;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.graph.selector.AndDependencySelector;
import org.eclipse.aether.util.graph.selector.ExclusionDependencySelector;
import org.eclipse.aether.util.graph.selector.OptionalDependencySelector;
import org.eclipse.aether.util.graph.selector.ScopeDependencySelector;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.eclipse.aether.util.graph.traverser.FatArtifactTraverser;
import org.eclipse.aether.util.graph.traverser.StaticDependencyTraverser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.apache.maven.repository.internal.MavenRepositorySystemUtils.newSession;

public class AetherUtils {

    static RepositorySystem newRepositorySystem() {

        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();

        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);

        locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
            @Override
            public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
                exception.printStackTrace();
            }
        });

        return locator.getService(RepositorySystem.class);

    }

    static void setFiltering(DefaultRepositorySystemSession session, boolean set){

        if (set){
            DependencySelector depFilter =
                    new AndDependencySelector(new ScopeDependencySelector(JavaScopes.TEST, JavaScopes.SYSTEM, JavaScopes.PROVIDED),
                            new ExclusionDependencySelector(), new OptionalDependencySelector());

            session.setDependencySelector(depFilter);
        }else{
            DependencySelector depFilter =
                    new AndDependencySelector(new ScopeDependencySelector(JavaScopes.TEST, JavaScopes.SYSTEM),
                            new ExclusionDependencySelector());

            session.setDependencySelector(depFilter);
        }
    }

    static DefaultRepositorySystemSession newRepositorySystemSession(RepositorySystem system, String localRepositoryDir) {
        DefaultRepositorySystemSession session = newSession();

        LocalRepository localRepo = new LocalRepository(localRepositoryDir);

        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));


        DependencySelector depFilter =
                new AndDependencySelector(new ScopeDependencySelector("test", "system", "provided"),
                        new ExclusionDependencySelector(), new OptionalDependencySelector());
        session.setDependencySelector(depFilter);

        session.setDependencyTraverser(new StaticDependencyTraverser(true));
        session.setTransferListener(new ConsoleTransferListener());
        session.setRepositoryListener(new ConsoleRepositoryListener());

        //To get dirty trees
        //session.setDependencyGraphTransformer( null );
        session.setConfigProperty( ConflictResolver.CONFIG_PROP_VERBOSE, true );

        return session;
    }


    static List<RemoteRepository> newRemoteRepositories(RepositorySystem system, RepositorySystemSession session) {
        return new ArrayList<>(Collections.singletonList(newCentralRepository()));
    }


    static RemoteRepository newCentralRepository() {

        return new RemoteRepository.Builder("central", "default", "http://central.maven.org/maven2/").build();

    }

}
