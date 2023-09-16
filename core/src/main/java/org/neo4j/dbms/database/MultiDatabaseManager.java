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

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel.DATABASE_LABEL;
import static org.neo4j.kernel.database.NamedDatabaseId.NAMED_SYSTEM_DATABASE_ID;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.neo4j.dbms.api.DatabaseExistsException;
import org.neo4j.dbms.api.DatabaseManagementException;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.kernel.database.Database;
import org.neo4j.kernel.database.DatabaseIdFactory;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.logging.Log;

/**
 * The `MultiDatabaseManager` class is responsible for managing the lifecycle of multiple databases within the Neo4j instance.
 * It offers methods for creating, starting, stopping, and listing databases.
 *
 * Key Responsibilities:
 * - Creating a database: A synchronized method `createDatabase` is responsible for creating a new database context
 *   and adding it to the database repository. It also checks if the limit of databases is exceeded.
 * - Starting a database: Offers overloaded methods `startDatabase` to start a database by either named database ID or context.
 * - Stopping a database: Similar to starting, it provides methods to stop a database using different overloads.
 * - Listing all named database IDs: `listAllNamedDatabaseIds` method retrieves all database IDs, performing this action within a transaction.
 * - Logging: Throughout its operations, it logs informative and error messages to assist in tracking the behavior and potential issues.
 *
 * TODO notes indicate potential future modifications such as implementing a method to drop a database, checking if a database is already started,
 * removing the database limit check if needed, and managing the actual count of databases from the graph configuration.
 *
 * Exception Handling:
 * - Errors during the database operations like starting, stopping, or listing are logged with appropriate messages.
 * - Exceptions like `DatabaseExistsException` and `DatabaseManagementException` are used to handle specific error conditions.
 *
 * Dependencies:
 * - Uses `DatabaseRepository` for interacting with databases and managing their contexts.
 * - Utilizes `DatabaseContextFactory` for creating standalone database contexts.
 * - Utilizes Neo4j's `GlobalModule` for logging and other global dependencies like the `DatabaseOperationCounts.Counter`.
 *
 * Note: Certain methods and functionality are marked as TODO indicating pending implementations or decisions.
 */
public final class MultiDatabaseManager {

    private final DatabaseRepository<StandaloneDatabaseContext> databaseRepository;

    private final DatabaseContextFactory<StandaloneDatabaseContext, Optional<?>> databaseContextFactory;
    private final Log log;

    private final DatabaseOperationCounts.Counter counter;

    public MultiDatabaseManager(
            GlobalModule globalModule,
            DatabaseRepository<StandaloneDatabaseContext> databaseRepository,
            DatabaseContextFactory<StandaloneDatabaseContext, Optional<?>> databaseContextFactory) {
        this.log = globalModule.getLogService().getInternalLogProvider().getLog(this.getClass());
        this.databaseRepository = databaseRepository;
        this.databaseContextFactory = databaseContextFactory;
        this.counter = globalModule.getGlobalDependencies().resolveDependency(DatabaseOperationCounts.Counter.class);
    }

    /**
     * Create database with specified name.
     * Database name should be unique.
     * By default a database is in a started state when it is initially created.
     *
     * @param namedDatabaseId ID of database to create
     * @return database context for newly created database
     * @throws DatabaseExistsException In case if database with specified name already exists
     */
    // @Override
    public synchronized StandaloneDatabaseContext createDatabase(NamedDatabaseId namedDatabaseId) {
        requireNonNull(namedDatabaseId);
        log.info("Creating '%s'.", namedDatabaseId);
        checkDatabaseLimit(namedDatabaseId);
        StandaloneDatabaseContext databaseContext = databaseContextFactory.create(namedDatabaseId, Optional.empty());
        databaseRepository.add(namedDatabaseId, databaseContext);
        return databaseContext;
    }

    public void dropDatabase(NamedDatabaseId ignore) {
        // TODO: Implement
    }

    public void startDatabase(NamedDatabaseId namedDatabaseId) {
        // TODO: Check is isStarted is true.
        this.databaseRepository
                .getDatabaseContext(namedDatabaseId)
                .get()
                .database()
                .start();
        this.counter.increaseStartCount();
    }

    public void startDatabase(StandaloneDatabaseContext context) {
        var namedDatabaseId = context.database().getNamedDatabaseId();
        try {
            log.info("Starting '%s'.", namedDatabaseId);
            Database database = context.database();
            database.start();
        } catch (Throwable t) {

            log.error("Failed to start " + namedDatabaseId, t);
            context.fail(new UnableToStartDatabaseException(
                    format("An error occurred! Unable to start `%s`.", namedDatabaseId), t));
        }
    }

    public void stopDatabase(StandaloneDatabaseContext context) {
        var namedDatabaseId = context.database().getNamedDatabaseId();
        // Make sure that any failure (typically database panic) that happened until now is not interpreted as shutdown
        // failure
        context.clearFailure();
        try {
            log.info("Stopping '%s'.", namedDatabaseId);
            Database database = context.database();

            database.stop();
            log.info("Stopped '%s' successfully.", namedDatabaseId);
        } catch (Throwable t) {
            log.error("Failed to stop " + namedDatabaseId, t);
            context.fail(new DatabaseManagementException(
                    format("An error occurred! Unable to stop `%s`.", namedDatabaseId), t));
        }
    }

    public void stopDatabase(NamedDatabaseId namedDatabaseId, StandaloneDatabaseContext context) {
        try {
            context.database().stop();
        } catch (Throwable t) {
            log.error("Failed to stop " + namedDatabaseId, t);
            context.fail(t);
        }
    }

    // TODO: We can also remove this check completely if needed.
    private void checkDatabaseLimit(NamedDatabaseId namedDatabaseId) {

        // TODO:  Get the actual count from the Graph configuration.
        if (databaseRepository.registeredDatabases().size() >= 100) {
            throw new DatabaseManagementException(
                    "Could not create gdb: " + namedDatabaseId.name() + " because you have exceeded the limit of 100.");
        }
    }

    /**
     * Converts a given Node object into a NamedDatabaseId.
     *
     * This method extracts the "name" and "uuid" properties from the provided Node,
     * using them to create a NamedDatabaseId object. The "name" property is expected
     * to be a string representing the database name, and the "uuid" property should
     * be a string representation of the corresponding UUID.
     *
     * @param node The Node object containing the database properties.
     * @return A NamedDatabaseId object created from the extracted name and UUID.
     * @throws IllegalArgumentException If the properties "name" or "uuid" are not found or if the "uuid" property is not a valid UUID.
     */
    public NamedDatabaseId namedDatabaseIdFromNode(Node node) {

        String gdbName = (String) node.getProperty("name");
        UUID gdbUuid = UUID.fromString((String) node.getProperty("uuid"));
        return DatabaseIdFactory.from(gdbName, gdbUuid);
    }

    /**
     * Retrieves a set of all named database IDs within the system.
     *
     * This method begins a transaction with the system database and retrieves all the nodes
     * with the label defined by DATABASE_LABEL. It then maps each node to a NamedDatabaseId
     * object using the namedDatabaseIdFromNode method and collects them into a set.
     *
     * If any exceptions occur during this process, an error message is logged, but the
     * exception is not propagated further. In case of an exception, the method returns null.
     *
     * @return A set of NamedDatabaseId objects representing all named database IDs in the
     *         system, or null if an exception occurred.
     */
    public Set<NamedDatabaseId> listAllNamedDatabaseIds() {
        Set<NamedDatabaseId> namedDatabaseIds = null; // Initialize to an empty set

        try (var transaction = this.databaseRepository
                        .getDatabaseContext(NAMED_SYSTEM_DATABASE_ID)
                        .orElseThrow()
                        .databaseFacade()
                        .beginTx();
                var nodeStream = transaction.findNodes(DATABASE_LABEL).stream()) {

            namedDatabaseIds = nodeStream.map(this::namedDatabaseIdFromNode).collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("An error occurred trying to list all the gdbs. Error:", e);
        }

        return namedDatabaseIds;
    }
}
