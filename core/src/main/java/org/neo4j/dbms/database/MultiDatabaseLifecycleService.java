/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.neo4j.dbms.database;

import static org.neo4j.function.ThrowingAction.executeAll;
import static org.neo4j.kernel.database.NamedDatabaseId.NAMED_SYSTEM_DATABASE_ID;
import static org.neo4j.kernel.database.NamedDatabaseId.SYSTEM_DATABASE_NAME;

import java.util.Optional;
import java.util.UUID;
import org.neo4j.dbms.api.DatabaseManagementException;
import org.neo4j.dbms.api.DatabaseNotFoundException;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.database.DatabaseIdFactory;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;

/**
 * The `MultiDatabaseLifecycleService` class is responsible for managing the lifecycle
 * operations (creation, initialization, starting, and stopping) for multiple databases within a Neo4j instance.
 * It handles tasks related to system databases, default databases, and all other databases.
 *
 * - It uses the `MultiDatabaseManager` to perform various database operations like creating and starting databases.
 * - The `DatabaseRepository` is used to interact with databases and retrieve information about them, such as database contexts.
 * - System databases are handled using the `SystemDatabaseStarter` inner class.
 * - Default databases are handled through initialization using the `DefaultDatabaseStarter` inner class.
 * - All other databases are started using the `AllDatabasesStarter` inner class and stopped using the `AllDatabaseStopper` inner class.
 * - Exception handling and logging are integral parts of these operations, with a focus on detailed error messages for failures during initialization or shutdown.
 *
 * TODO notes within the class indicate potential future modifications, such as enhancing the stopping mechanism for all graph databases, not just the default.
 */
public final class MultiDatabaseLifecycleService {
    private final DatabaseRepository<StandaloneDatabaseContext> databaseRepository;
    private final String defaultGdbName;

    private final Log log;

    private final MultiDatabaseManager multiDatabaseManager;

    public MultiDatabaseLifecycleService(
            MultiDatabaseManager multiDatabaseManager,
            DatabaseRepository<StandaloneDatabaseContext> databaseRepository,
            String defaultGdbName,
            DatabaseContextFactory<StandaloneDatabaseContext, Optional<?>> databaseContextFactory,
            LogProvider logProvider) {
        this.multiDatabaseManager = multiDatabaseManager;
        this.databaseRepository = databaseRepository;
        this.defaultGdbName = defaultGdbName;

        this.log = logProvider.getLog(getClass());
    }

    public Lifecycle systemDatabaseStarter() {
        return new SystemDatabaseStarter();
    }

    public Lifecycle defaultDatabaseStarter() {
        return new DefaultDatabaseStarter();
    }

    public Lifecycle allDatabaseStarter() {
        return new AllDatabasesStarter();
    }

    public Lifecycle allDatabaseShutdown() {
        return new AllDatabaseStopper();
    }

    private StandaloneDatabaseContext getSystemDatabaseContext() {
        return databaseRepository
                .getDatabaseContext(NAMED_SYSTEM_DATABASE_ID)
                .orElseThrow(() -> new DatabaseNotFoundException("database not found: " + SYSTEM_DATABASE_NAME));
    }

    private Optional<StandaloneDatabaseContext> getDefaultDatabaseContext() {
        return databaseRepository.getDatabaseContext(defaultGdbName);
    }

    private synchronized void initDefaultDatabase() {
        var defaultDatabaseId = databaseRepository
                .databaseIdRepository()
                .getByName(defaultGdbName)
                .orElseThrow(() -> new DatabaseNotFoundException(
                        "The default graph database was not found. Default name: " + defaultGdbName));
        if (databaseRepository.getDatabaseContext(defaultDatabaseId).isPresent()) {
            throw new DatabaseManagementException(
                    "Default Graph Database Initialization failure. The databaseId with ID: " + defaultDatabaseId
                            + " already exists.");
        }
        var context = multiDatabaseManager.createDatabase(defaultDatabaseId);
        multiDatabaseManager.startDatabase(context);
    }

    NamedDatabaseId namedDatabaseIdFromNode(Node node) {
        String name = (String) node.getProperty("name");
        UUID uuid = UUID.fromString((String) node.getProperty("uuid"));
        return DatabaseIdFactory.from(name, uuid);
    }

    // TODO: We will check if already started and start then just like initialize default dbs.
    private synchronized void startOtherDatabases() {

        try {

            multiDatabaseManager.listAllNamedDatabaseIds().forEach(namedDatabaseId -> {

                // Create the database and add to the database repository.
                if (!namedDatabaseId.isSystemDatabase()
                        && !namedDatabaseId.name().equals(defaultGdbName)) {

                    var context = multiDatabaseManager.createDatabase(namedDatabaseId);
                    multiDatabaseManager.startDatabase(context);
                }
            });

        } catch (Exception e) {

            log.error(" An error occurred trying to start databases.", e);
        }
    }

    private class SystemDatabaseStarter extends LifecycleAdapter {
        @Override
        public void init() {
            multiDatabaseManager.createDatabase(NAMED_SYSTEM_DATABASE_ID);
        }

        @Override
        public void start() {
            multiDatabaseManager.startDatabase(getSystemDatabaseContext());
        }
    }

    // TODO: Make this stop all gdbs not just the default.
    private class AllDatabaseStopper extends LifecycleAdapter {
        @Override
        public void stop() throws Exception {
            var standaloneDatabaseContext = getDefaultDatabaseContext();
            standaloneDatabaseContext.ifPresent(multiDatabaseManager::stopDatabase);

            StandaloneDatabaseContext systemContext = getSystemDatabaseContext();
            multiDatabaseManager.stopDatabase(systemContext);

            /**
             * TODO:  The system database should be the last one stopped according to the test LifeCycles Test.
             */
            databaseRepository.registeredDatabases().forEach((namedDatabaseId, databaseContext) -> {
                // We do not stop the system database as that is stopped manually through another lifecycle

                if (!namedDatabaseId.isSystemDatabase()) {

                    multiDatabaseManager.stopDatabase(namedDatabaseId, databaseContext);
                }
            });

            // TODO: Shutdown the rest of the databases and pass them to the execute all as well...
            executeAll(
                    () -> standaloneDatabaseContext.ifPresent(this::throwIfUnableToStop),
                    () -> throwIfUnableToStop(systemContext));
        }

        private void throwIfUnableToStop(StandaloneDatabaseContext ctx) {

            if (!ctx.isFailed()) {
                return;
            }

            // If we have not been able to start the database instance, then
            // we do not want to add a compounded error due to not being able
            // to stop the database.
            if (ctx.failureCause() instanceof UnableToStartDatabaseException) {
                return;
            }

            throw new DatabaseManagementException(
                    "Failed to stop " + ctx.database().getNamedDatabaseId().name() + " database.", ctx.failureCause());
        }
    }

    private class AllDatabasesStarter extends LifecycleAdapter {

        @Override
        public void start() {
            // multiDatabaseManager.startDatabase(getSystemDatabaseContext());
            startOtherDatabases();
        }
    }

    private class DefaultDatabaseStarter extends LifecycleAdapter {
        @Override
        public void start() {
            initDefaultDatabase();
        }
    }
}
