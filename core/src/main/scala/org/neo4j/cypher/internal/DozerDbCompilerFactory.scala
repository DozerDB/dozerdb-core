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
 *  Modifications Copyright (c) DozerDB
 *  https://dozerdb.org
 */

package org.neo4j.cypher.internal

import org.neo4j.cypher.internal.cache.CypherQueryCaches
import org.neo4j.cypher.internal.compiler.CypherPlannerConfiguration
import org.neo4j.cypher.internal.options.CypherPlannerOption
import org.neo4j.cypher.internal.options.CypherRuntimeOption
import org.neo4j.cypher.internal.options.CypherUpdateStrategy
import org.neo4j.cypher.internal.planning.CypherPlanner
import org.neo4j.cypher.internal.runtime.CypherRuntimeConfiguration
import org.neo4j.kernel.GraphDatabaseQueryService
import org.neo4j.kernel.database.DatabaseReferenceRepository
import org.neo4j.logging.InternalLog
import org.neo4j.logging.InternalLogProvider
import org.neo4j.monitoring

/**
 * Factory which creates cypher compilers.
 */
class DozerDbCompilerFactory(
  graph: GraphDatabaseQueryService,
  kernelMonitors: monitoring.Monitors,
  logProvider: InternalLogProvider,
  plannerConfig: CypherPlannerConfiguration,
  runtimeConfig: CypherRuntimeConfiguration,
  queryCaches: CypherQueryCaches
) extends CompilerFactory {

  private val log: InternalLog = logProvider.getLog(getClass)

  override def supportsAdministrativeCommands(): Boolean = plannerConfig.planSystemCommands

  override def createCompiler(
    cypherPlanner: CypherPlannerOption,
    cypherRuntime: CypherRuntimeOption,
    cypherUpdateStrategy: CypherUpdateStrategy,
    executionEngineProvider: () => ExecutionEngine
  ): Compiler = {

    val dependencies = graph.getDependencyResolver

    val planner =
      CypherPlanner(
        plannerConfig,
        MasterCompiler.CLOCK,
        kernelMonitors,
        log,
        queryCaches,
        cypherPlanner,
        cypherUpdateStrategy,
        dependencies.resolveDependency(classOf[DatabaseReferenceRepository])
      )

    val runtime =
      if (plannerConfig.planSystemCommands)
        DozerDbAdministrationCommandRuntime(executionEngineProvider(), graph.getDependencyResolver)
      else
        CommunityRuntimeFactory.getRuntime(cypherRuntime, plannerConfig.useErrorsOverWarnings())

    CypherCurrentCompiler(
      planner,
      runtime,
      CommunityRuntimeContextManager(log, runtimeConfig),
      kernelMonitors,
      queryCaches
    )
  }
}
