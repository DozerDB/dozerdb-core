/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
/*
 * Modifications Copyright (c) DozerDB
 * https://dozerdb.org
 */
package org.neo4j.dbms.database;

import static java.util.Objects.requireNonNull;
import static org.neo4j.configuration.GraphDatabaseSettings.default_database;
import static org.neo4j.kernel.database.NamedDatabaseId.NAMED_SYSTEM_DATABASE_ID;

import org.neo4j.dbms.api.DatabaseExistsException;
import org.neo4j.dbms.api.DatabaseManagementException;
import org.neo4j.dbms.api.DatabaseNotFoundException;
import org.neo4j.dbms.systemgraph.CommunityTopologyGraphDbmsModel;
import org.neo4j.dbms.systemgraph.TopologyGraphDbmsModel;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.graphdb.factory.module.edition.AbstractEditionModule;
import org.neo4j.kernel.database.Database;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.logging.Log;

public final class MultiDatabaseManager extends AbstractDatabaseManager<StandaloneDatabaseContext> {

    private final Log log;
    private final DatabaseOperationCounts.Counter counter;

    public MultiDatabaseManager(GlobalModule globalModule, AbstractEditionModule edition) {
        super(globalModule, edition, true);
        this.log = globalModule.getLogService().getInternalLogProvider().getLog(this.getClass());
        this.counter = globalModule.getGlobalDependencies().resolveDependency(DatabaseOperationCounts.Counter.class);
    }

    @Override
    public void initialiseSystemDatabase() {
        createDatabase(NAMED_SYSTEM_DATABASE_ID);
    }

    @Override
    public void initialiseDefaultDatabase() {
        String databaseName = config.get(default_database);
        NamedDatabaseId namedDatabaseId = databaseIdRepository()
                .getByName(databaseName)
                .orElseThrow(() -> new DatabaseNotFoundException("Default database not found: " + databaseName));
        StandaloneDatabaseContext context = createDatabase(namedDatabaseId);
        if (manageDatabasesOnStartAndStop) {
            this.startDatabase(namedDatabaseId, context);
        }

        // TODO:  We can refactor this to be called elsewhere.
        initializeAllGdbs();
    }

    /**
     * The method 'initializeAllGdbs' is responsible for initializing all Graph Databases (GDBs) in
     * the system, excluding the system and default databases. This process involves creating each
     * database and starting it if the 'manageDatabasesOnStartAndStop' property is set to true.
     * <p>
     * The initialization process is as follows: - A transaction on the system database is begun. - A
     * new 'CommunityTopologyGraphDbmsModel' object is created using the transaction. - All
     * DatabaseIds are retrieved from the model. - These Ids are filtered to exclude the system
     * database and the default database. - For each of the remaining DatabaseIds, a new database is
     * created and added to the map. If 'manageDatabasesOnStartAndStop' is set to true, the database
     * is also started.
     * <p>
     * The method catches and handles any exceptions that may occur during the process, currently
     * outputting an error message to standard output. Future implementation should involve logging
     * this error.
     * <p>
     * This method does not accept any parameters or return any values.
     */
    private void initializeAllGdbs() {
        TopologyGraphDbmsModel topologyGraphDbmsModel;

        try (var tx = this.getSystemDatabaseContext().databaseFacade().beginTx()) {
            topologyGraphDbmsModel = new CommunityTopologyGraphDbmsModel(tx);

            // Lets initialize all the graphs not system or default.
            topologyGraphDbmsModel.getAllDatabaseIds().stream()
                    .filter(namedGDBId -> {
                        return !namedGDBId.isSystemDatabase()
                                && !namedGDBId.name().equals(config.get(default_database));
                    })
                    .forEach(namedGdhId -> {
                        // Creates the database which adds to the map.
                        StandaloneDatabaseContext context2 = createDatabase(namedGdhId);
                        if (manageDatabasesOnStartAndStop) {
                            this.startDatabase(namedGdhId, context2);
                        }
                    });
        } catch (Exception e) {

            log.error("  ERROR trying to initialize topologyGraphDbmsService!   Error: {}", e.getMessage());
        }
    }

    /**
     * Create database with specified name. Database name should be unique. By default a database is
     * in a started state when it is initially created.
     *
     * @param namedDatabaseId ID of database to create
     * @return database context for newly created database
     * @throws DatabaseExistsException In case if database with specified name already exists
     */
    @Override
    public synchronized StandaloneDatabaseContext createDatabase(NamedDatabaseId namedDatabaseId) {
        requireNonNull(namedDatabaseId);
        log.info("Creating '%s'.", namedDatabaseId);
        checkDatabaseLimit(namedDatabaseId);
        StandaloneDatabaseContext databaseContext = createDatabaseContext(namedDatabaseId, DatabaseOptions.EMPTY);
        databaseMap.put(namedDatabaseId, databaseContext);
        return databaseContext;
    }

    @Override
    protected StandaloneDatabaseContext createDatabaseContext(
            NamedDatabaseId namedDatabaseId, DatabaseOptions databaseOptions) {
        var databaseCreationContext = newDatabaseCreationContext(
                namedDatabaseId,
                databaseOptions,
                globalModule.getGlobalDependencies(),
                globalModule.getGlobalMonitors());
        var kernelDatabase = new Database(databaseCreationContext);
        return new StandaloneDatabaseContext(kernelDatabase);
    }

    @Override
    public void dropDatabase(NamedDatabaseId ignore) {
        throw new DatabaseManagementException("Default database manager does not support database drop.");
    }

    @Override
    public synchronized void upgradeDatabase(NamedDatabaseId namedDatabaseId) throws DatabaseNotFoundException {
        StandaloneDatabaseContext context = getDatabaseContext(namedDatabaseId)
                .orElseThrow(() -> new DatabaseNotFoundException("Database not found: " + namedDatabaseId));
        Database database = context.database();
        log.info("Upgrading '%s'.", namedDatabaseId);
        context.fail(null); // Clear any failed state, e.g. due to format being too old on startup.
        try {
            database.upgrade(true);
        } catch (Throwable throwable) {
            String message = "Failed to upgrade " + namedDatabaseId;
            context.fail(throwable);
            throw new DatabaseManagementException(message, throwable);
        }
    }

    @Override
    public void stopDatabase(NamedDatabaseId namedDatabaseId) {
        this.getDatabaseContext(namedDatabaseId).get().database().stop();
        this.counter.increaseStopCount();
    }

    @Override
    public void startDatabase(NamedDatabaseId namedDatabaseId) {
        this.getDatabaseContext(namedDatabaseId).get().database().start();

        this.counter.increaseStartCount();
    }

    @Override
    protected void stopDatabase(NamedDatabaseId namedDatabaseId, StandaloneDatabaseContext context) {
        try {
            super.stopDatabase(namedDatabaseId, context);
        } catch (Throwable t) {
            log.error("Failed to stop " + namedDatabaseId, t);
            context.fail(t);
        }
    }

    @Override
    protected void startDatabase(NamedDatabaseId namedDatabaseId, StandaloneDatabaseContext context) {
        try {
            super.startDatabase(namedDatabaseId, context);
        } catch (Throwable t) {
            log.error("Failed to start " + namedDatabaseId, t);
            context.fail(t);
        }
    }

    // TODO: We can also remove this check completely if needed.
    private void checkDatabaseLimit(NamedDatabaseId namedDatabaseId) {
        // TODO:  Get the actual count from the Graph configuration.
        if (databaseMap.size() >= 100) {
            throw new DatabaseManagementException(
                    "Could not create gdb: " + namedDatabaseId.name() + " because you have exceeded the limit of 100.");
        }
    }
}
