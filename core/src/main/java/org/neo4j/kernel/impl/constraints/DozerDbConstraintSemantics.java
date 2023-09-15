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
package org.neo4j.kernel.impl.constraints;

import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.common.TokenNameLookup;
import org.neo4j.internal.kernel.api.CursorFactory;
import org.neo4j.internal.kernel.api.NodeCursor;
import org.neo4j.internal.kernel.api.NodeLabelIndexCursor;
import org.neo4j.internal.kernel.api.PropertyCursor;
import org.neo4j.internal.kernel.api.Read;
import org.neo4j.internal.kernel.api.RelationshipScanCursor;
import org.neo4j.internal.kernel.api.RelationshipTypeIndexCursor;
import org.neo4j.internal.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.internal.schema.ConstraintDescriptor;
import org.neo4j.internal.schema.LabelSchemaDescriptor;
import org.neo4j.internal.schema.RelationTypeSchemaDescriptor;
import org.neo4j.internal.schema.constraints.NodeKeyConstraintDescriptor;
import org.neo4j.internal.schema.constraints.UniquenessConstraintDescriptor;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.storageengine.api.StandardConstraintRuleAccessor;
import org.neo4j.storageengine.api.StorageReader;
import org.neo4j.storageengine.api.txstate.ReadableTransactionState;
import org.neo4j.storageengine.api.txstate.TxStateVisitor;

@ServiceProvider
public class DozerDbConstraintSemantics extends StandardConstraintSemantics {

    protected final StandardConstraintRuleAccessor accessor = new StandardConstraintRuleAccessor();

    public DozerDbConstraintSemantics() {
        this(1);
    }

    protected DozerDbConstraintSemantics(int priority) {

        super(priority);
    }

    @Override
    public String getName() {
        return "dozerDbConstraints";
    }

    @Override
    public void assertNodeKeyConstraintAllowed(LabelSchemaDescriptor descriptor)
            throws CreateConstraintFailureException {}

    @Override
    public void validateNodeKeyConstraint(
            NodeLabelIndexCursor allNodes,
            NodeCursor nodeCursor,
            PropertyCursor propertyCursor,
            LabelSchemaDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        // TODO: Implement
    }

    @Override
    public void validateNodePropertyExistenceConstraint(
            NodeLabelIndexCursor allNodes,
            NodeCursor nodeCursor,
            PropertyCursor propertyCursor,
            LabelSchemaDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {

        // TODO: We need to loop through the cursor and check each one for property constraints using property ids in
        // LabelSchemaDescriptor.
        // TODO: For each node we need to pass to validateNodePropertyExistenceConstraint
        // TODO: Implement

    }

    @Override
    public void validateRelationshipPropertyExistenceConstraint(
            RelationshipScanCursor relationshipCursor,
            PropertyCursor propertyCursor,
            RelationTypeSchemaDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        // TODO: Implement
    }

    @Override
    public void validateRelationshipPropertyExistenceConstraint(
            RelationshipTypeIndexCursor allRelationships,
            RelationshipScanCursor relationshipCursor,
            PropertyCursor propertyCursor,
            RelationTypeSchemaDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        // TODO: Implement
    }

    @Override
    public ConstraintDescriptor readConstraint(ConstraintDescriptor constraint) {
        return constraint;
    }

    protected ConstraintDescriptor readNonStandardConstraint(ConstraintDescriptor constraint, String errorMessage) {
        return constraint;
    }

    @Override
    public ConstraintDescriptor createUniquenessConstraintRule(
            long ruleId, UniquenessConstraintDescriptor descriptor, long indexId) {
        return accessor.createUniquenessConstraintRule(ruleId, descriptor, indexId);
    }

    @Override
    public ConstraintDescriptor createNodeKeyConstraintRule(
            long ruleId, NodeKeyConstraintDescriptor descriptor, long indexId) throws CreateConstraintFailureException {
        // We want to use the accessor to create the constraint rule.
        return accessor.createNodeKeyConstraintRule(ruleId, descriptor, indexId);
    }

    @Override
    public ConstraintDescriptor createExistenceConstraint(long ruleId, ConstraintDescriptor descriptor)
            throws CreateConstraintFailureException {
        return accessor.createExistenceConstraint(ruleId, descriptor);
    }

    @Override
    public TxStateVisitor decorateTxStateVisitor(
            StorageReader storageReader,
            Read read,
            CursorFactory cursorFactory,
            ReadableTransactionState readableTransactionState,
            TxStateVisitor txStateVisitor,
            CursorContext cursorContext,
            MemoryTracker memoryTracker) {
        return !readableTransactionState.hasDataChanges()
                ? txStateVisitor
                : ConstraintChecker.constraintChecker(storageReader)
                        .visit(cursorContext, cursorFactory, memoryTracker, read, txStateVisitor);
    }

    @Override
    public void validateNodePropertyExistenceConstraint(
            NodeCursor nodeCursor,
            PropertyCursor propertyCursor,
            LabelSchemaDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        // TODO: We need to loop through the nodeCursor and check that the LabelSchemaDescriptor properties are all
        // present.
        // TODO: If we find a bad one we throw an exception.
        // TODO: Implement

    }

    @Override
    public void validateNodeKeyConstraint(
            NodeCursor nodeCursor,
            PropertyCursor propertyCursor,
            LabelSchemaDescriptor descriptor,
            TokenNameLookup tokenNameLookup)
            throws CreateConstraintFailureException {
        validateNodePropertyExistenceConstraint(nodeCursor, propertyCursor, descriptor, tokenNameLookup);
    }
}
