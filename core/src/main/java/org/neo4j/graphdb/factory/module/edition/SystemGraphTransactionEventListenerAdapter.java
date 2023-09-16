/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *
 *
 */
package org.neo4j.graphdb.factory.module.edition;

import static org.neo4j.configuration.GraphDatabaseSettings.initial_default_database;
import static org.neo4j.kernel.database.NamedDatabaseId.NAMED_SYSTEM_DATABASE_ID;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.neo4j.configuration.Config;
import org.neo4j.dbms.database.MultiDatabaseManager;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.event.TransactionData;
import org.neo4j.graphdb.event.TransactionEventListenerAdapter;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.logging.InternalLog;

/**
 * The SystemGraphTransactionEventListenerAdapter class extends the TransactionEventListenerAdapter
 * and overrides its methods to handle the transaction events in the context of the system graph database.
 * This class is used to initialize and manage other databases besides the system and default ones.
 * <p>
 * This class has the ability to retrieve the id of a named database,
 * and to handle the updates after a commit in a system database is performed.
 * <p>
 * The key methods of this class are:
 * <p>
 * - getNamedDatabaseIdForName(String nameToFind): Retrieves the id of a database with a specified name.
 * - afterCommit(TransactionData txData, Object state, GraphDatabaseService systemDatabase): Handles changes
 * in the system database after a commit has been performed. Specifically, it checks the status and name of
 * the assigned node properties. If these properties meet certain conditions, it retrieves the named database id
 * associated with the name, creates the database if it exists, and starts the database.
 * <p>
 * This class also contains a private final instance of DatabaseManager, which is used to handle database operations.
 */
public class SystemGraphTransactionEventListenerAdapter extends TransactionEventListenerAdapter<Object> {

    protected final Config config;
    private final MultiDatabaseManager databaseManager;
    private final List<String> gdbsToIgnore;

    private final InternalLog log;

    // protected final Log userLog;
    public SystemGraphTransactionEventListenerAdapter(
            MultiDatabaseManager multiDatabaseManager, GlobalModule globalModule) {

        this.databaseManager = multiDatabaseManager;

        this.config = globalModule.getGlobalConfig();

        log = globalModule.getLogService().getInternalLogProvider().getLog(this.getClass());
        String defaultDatabaseName = config.get(initial_default_database);
        String systemDatabaseName = NAMED_SYSTEM_DATABASE_ID.name();

        gdbsToIgnore = List.of(defaultDatabaseName, systemDatabaseName);
    }

    public NamedDatabaseId getNamedDatabaseIdForName(String nameToFind) {

        return this.databaseManager.listAllNamedDatabaseIds().stream()
                .filter(namedDatabaseId -> namedDatabaseId.name().equals(nameToFind))
                .findFirst()
                .orElse(null);
    } // End

    @Override
    public void afterCommit(TransactionData txData, Object state, GraphDatabaseService systemDatabase) {

        AtomicReference<String> newStatus = new AtomicReference<>();
        AtomicReference<String> oldStatus = new AtomicReference<>();
        AtomicReference<String> name = new AtomicReference<>();
        txData.assignedNodeProperties().forEach(pen -> {
            if (pen.key().equals("status")) {

                newStatus.set(pen.value().toString());
                if (pen.previouslyCommittedValue() != null) {
                    oldStatus.set(pen.previouslyCommittedValue().toString());
                }
            } // End if.

            if (pen.key().equals("name")) {
                name.set(pen.value().toString());
            }
        });

        // We ignore / return if the transaction is related to the system or default graph database.
        if (name.get() == null || gdbsToIgnore.contains(name.get())) {
            return;
        }

        if (newStatus.get() != null && name.get() != null) {

            NamedDatabaseId nId = getNamedDatabaseIdForName(name.get());

            if (nId != null) {

                databaseManager.createDatabase(nId);
                databaseManager.startDatabase(nId);
                log.info(" Database " + name.get() + " was started.");

            } else {
                log.warn(" Database " + name.get() + " was not found.");
            }
        }

        // We should only have one node for the commits we are watching.

    }
}
