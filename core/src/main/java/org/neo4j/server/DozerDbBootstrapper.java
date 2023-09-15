/*
 * Copyright (c) DozerDB.org
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.neo4j.server;

import static org.neo4j.kernel.impl.factory.DbmsInfo.ENTERPRISE;

import org.neo4j.configuration.Config;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.facade.DatabaseManagementServiceFactory;
import org.neo4j.graphdb.facade.GraphDatabaseDependencies;
import org.neo4j.graphdb.factory.module.edition.DozerDbEditionModule;

public class DozerDbBootstrapper extends NeoBootstrapper {

    private static final String DOZERDB_MESSAGE =
            "\n\n*****************************************************************************\n"
                    + " *********************** Enhanced By DozerDB Plugin ***********************\n"
                    + "*****************************************************************************\n\n";

    @Override
    protected DatabaseManagementService createNeo(Config config, GraphDatabaseDependencies dependencies) {
        super.getLog().info(DOZERDB_MESSAGE);
        DatabaseManagementServiceFactory facadeFactory =
                new DatabaseManagementServiceFactory(ENTERPRISE, DozerDbEditionModule::new);
        return facadeFactory.build(config, dependencies);
    }
}
