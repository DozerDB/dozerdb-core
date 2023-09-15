/*
 * Copyright (c) DozerDB.org
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.neo4j.kernel.impl.constraints;

import org.eclipse.collections.api.IntIterable;
import org.eclipse.collections.api.set.primitive.LongSet;
import org.eclipse.collections.api.set.primitive.MutableIntSet;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.neo4j.exceptions.KernelException;
import org.neo4j.internal.kernel.api.CursorFactory;
import org.neo4j.internal.kernel.api.NodeCursor;
import org.neo4j.internal.kernel.api.PropertyCursor;
import org.neo4j.internal.kernel.api.Read;
import org.neo4j.internal.kernel.api.RelationshipScanCursor;
import org.neo4j.internal.kernel.api.TokenSet;
import org.neo4j.internal.kernel.api.exceptions.schema.ConstraintValidationException;
import org.neo4j.io.IOUtils;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.api.exceptions.schema.NodePropertyExistenceException;
import org.neo4j.kernel.api.exceptions.schema.RelationshipPropertyExistenceException;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.storageengine.api.StorageProperty;
import org.neo4j.storageengine.api.txstate.RelationshipModifications;
import org.neo4j.storageengine.api.txstate.TxStateVisitor;

/**
 * The `ConstraintDelegator` class extends the `TxStateVisitor.Delegator` to validate constraints on
 * nodes and relationships within the Neo4j database during transaction states.
 * <p>
 * This class is responsible for: - Checking constraints on nodes when node properties or labels
 * change. - Checking constraints on relationships when relationship properties change or new
 * relationships are created. - Ensuring that specific nodes and relationships meet the required
 * property existence constraints.
 * <p>
 * Internally, the class utilizes `NodeCursor`, `PropertyCursor`, and `RelationshipScanCursor` to
 * interact with Neo4j's storage layer and retrieve node/relationship properties and labels. It then
 * checks these against constraints defined by the `ConstraintChecker` to determine if the node or
 * relationship violates any constraints.
 * <p>
 * The class also provides utility methods to validate existence, populate properties, and validate
 * properties of nodes and relationships against the defined constraints.
 */
public class ConstraintDelegator extends TxStateVisitor.Delegator {

    private final ConstraintChecker constraintChecker;
    private final NodeCursor nodeCursor;
    private final PropertyCursor propertyCursor;
    private final Read read;
    private final RelationshipScanCursor relationshipScanCursor;

    private final MutableIntSet mutablePropertyKeys = new IntHashSet();

    public ConstraintDelegator(
            ConstraintChecker constraintChecker,
            CursorContext cursorContext,
            CursorFactory cursorFactory,
            MemoryTracker memoryTracker,
            TxStateVisitor next,
            Read read) {
        super(next);
        this.constraintChecker = constraintChecker;
        this.nodeCursor = cursorFactory.allocateFullAccessNodeCursor(cursorContext);
        this.propertyCursor = cursorFactory.allocateFullAccessPropertyCursor(cursorContext, memoryTracker);
        this.read = read;
        this.relationshipScanCursor = cursorFactory.allocateRelationshipScanCursor(cursorContext);
    }

    @Override
    public void visitNodePropertyChanges(
            long id, Iterable<StorageProperty> added, Iterable<StorageProperty> changed, IntIterable removed)
            throws ConstraintValidationException {
        this.checkNode(id);
        super.visitNodePropertyChanges(id, added, changed, removed);
    }

    @Override
    public void visitNodeLabelChanges(long id, LongSet added, LongSet removed) throws ConstraintValidationException {
        this.checkNode(id);
        super.visitNodeLabelChanges(id, added, removed);
    }

    @Override
    public void visitRelationshipModifications(RelationshipModifications relationshipModifications)
            throws ConstraintValidationException {
        relationshipModifications
                .creations()
                .forEach((id, type, startNode, endNode, addedProperties) -> this.checkRel(id));
        super.visitRelationshipModifications(relationshipModifications);
    }

    @Override
    public void visitRelPropertyChanges(
            long id,
            int type,
            long startNode,
            long endNode,
            Iterable<StorageProperty> added,
            Iterable<StorageProperty> changed,
            IntIterable removed)
            throws ConstraintValidationException {
        this.checkRel(id);
        super.visitRelPropertyChanges(id, type, startNode, endNode, added, changed, removed);
    }

    @Override
    public void close() throws KernelException {
        super.close();
        IOUtils.closeAllUnchecked(this.nodeCursor, this.relationshipScanCursor, this.propertyCursor);
    }

    /**
     * Checks constraints on the specified node by its ID. Validates that required properties are
     * present for the node.
     *
     * @param nodeId The ID of the node to check.
     * @throws NodePropertyExistenceException If the specified node does not meet the property
     *                                        existence constraints.
     */
    private void checkNode(long nodeId) throws NodePropertyExistenceException {
        // If no property constraints are defined, skip the check
        if (constraintChecker.getNodePropertyMap().isEmpty()) {
            return;
        }

        // Throw an exception if the node does not exist
        if (!nodeExists(nodeId)) {
            throw new IllegalStateException(String.format("Node with id = %d fails constraint checks.", nodeId));
        }

        // Get labels of the node
        TokenSet tokenSet = nodeCursor.labels();
        // Return if the node does not have any labels
        if (tokenSet.numberOfTokens() == 0) {
            return;
        }

        // Populate properties of the node
        populateNodeProperties();
        // Check node properties against constraints
        constraintChecker.checkNode(nodeId, tokenSet, mutablePropertyKeys);
    }

    /**
     * Checks if a node with the given ID exists.
     *
     * @param nodeId The ID of the node to check.
     * @return true if the node exists; false otherwise.
     */
    private boolean nodeExists(long nodeId) {
        read.singleNode(nodeId, nodeCursor);
        return nodeCursor.next();
    }

    /**
     * Populates the mutablePropertyKeys set with properties from the current node.
     */
    private void populateNodeProperties() {
        // Clear any existing properties
        mutablePropertyKeys.clear();
        // Get properties of the node
        nodeCursor.properties(propertyCursor);
        // Add each property key to the set
        while (propertyCursor.next()) {
            mutablePropertyKeys.add(propertyCursor.propertyKey());
        }
    }

    /**
     * Checks the constraints on the specified relationship by its ID. It validates that required
     * properties are present for the relationship.
     *
     * @param relId The ID of the relationship to check.
     * @throws RelationshipPropertyExistenceException If the specified relationship does not meet the
     *                                                property existence constraints.
     */
    private void checkRel(long relId) throws RelationshipPropertyExistenceException {
        if (constraintChecker.getRelPropertyMap().isEmpty()) {
            return;
        }

        if (!relationshipExists(relId)) {
            throw new IllegalStateException(
                    String.format("Edge/Relationship with id = %d fails constraint checks.", relId));
        }

        int typeToCheck = relationshipScanCursor.type();
        int[] shouldHavePropertiesArray = constraintChecker.getRelPropertyMap().get(typeToCheck);

        if (shouldHavePropertiesArray == null) {
            return;
        }

        populateRelationshipProperties();
        validateRelationshipProperties(relId, typeToCheck, shouldHavePropertiesArray);
    }

    /**
     * Checks if a relationship with the given ID exists.
     *
     * @param relId The ID of the relationship to check.
     * @return true if the relationship exists; false otherwise.
     */
    private boolean relationshipExists(long relId) {
        read.singleRelationship(relId, relationshipScanCursor);
        return relationshipScanCursor.next();
    }

    /**
     * Populates the mutablePropertyKeys set with properties from the current relationship.
     */
    private void populateRelationshipProperties() {
        mutablePropertyKeys.clear();
        relationshipScanCursor.properties(propertyCursor);
        while (propertyCursor.next()) {
            mutablePropertyKeys.add(propertyCursor.propertyKey());
        }
    }

    /**
     * Validates if the specified relationship has all the required properties as specified in the
     * shouldHavePropertiesArray. If a required property is missing, it triggers a constraint
     * failure.
     *
     * @param relId                     The ID of the relationship to check.
     * @param typeToCheck               The type of the relationship.
     * @param shouldHavePropertiesArray Array of property keys that should be present.
     * @throws RelationshipPropertyExistenceException If a required property is missing.
     */
    private void validateRelationshipProperties(long relId, int typeToCheck, int[] shouldHavePropertiesArray)
            throws RelationshipPropertyExistenceException {
        for (int propertyKey : shouldHavePropertiesArray) {
            if (!mutablePropertyKeys.contains(propertyKey)) {
                constraintChecker.relConstraintFailure(relId, typeToCheck, propertyKey);
            }
        }
    }
}
