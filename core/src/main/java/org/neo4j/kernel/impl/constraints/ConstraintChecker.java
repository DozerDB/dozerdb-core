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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import org.eclipse.collections.api.iterator.MutableLongIterator;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.api.set.primitive.IntSet;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.neo4j.collection.PrimitiveArrays;
import org.neo4j.internal.kernel.api.CursorFactory;
import org.neo4j.internal.kernel.api.Read;
import org.neo4j.internal.kernel.api.TokenSet;
import org.neo4j.internal.kernel.api.exceptions.schema.ConstraintValidationException.Phase;
import org.neo4j.internal.schema.ConstraintDescriptor;
import org.neo4j.internal.schema.LabelSchemaDescriptor;
import org.neo4j.internal.schema.RelationTypeSchemaDescriptor;
import org.neo4j.internal.schema.SchemaDescriptor;
import org.neo4j.internal.schema.SchemaProcessor;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.api.exceptions.schema.NodePropertyExistenceException;
import org.neo4j.kernel.api.exceptions.schema.RelationshipPropertyExistenceException;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.storageengine.api.StorageReader;
import org.neo4j.storageengine.api.txstate.TxStateVisitor;

/**
 * This class is responsible for checking and enforcing schema constraints on Neo4j graph entities,
 * namely nodes and relationships. It handles validation related to property existence for nodes and
 * relationships.
 */
public class ConstraintChecker {

    public static final ConstraintChecker EMPTY_CHECKER =
            new ConstraintChecker(null, Collections.emptyList(), Collections.emptyList()) {

                @Override
                TxStateVisitor visit(
                        CursorContext cursorContext,
                        CursorFactory cursorFactory,
                        MemoryTracker memoryTracker,
                        Read read,
                        TxStateVisitor txStateVisitor) {
                    return txStateVisitor;
                }
            };

    /**
     * A function that constructs a {@link ConstraintChecker} based on the given
     * {@link StorageReader}.
     *
     * <p>The function processes all constraints retrieved from the provided StorageReader. It
     * categorizes
     * these constraints into two lists: one for node label schema descriptors and another for
     * relation type schema descriptors, but only if the constraint enforces property existence.
     *
     * <p>If both the node label schema descriptors list and the relation type schema descriptors list
     * are
     * empty after processing all constraints, the function returns an empty checker (represented by
     * the EMPTY_CHECKER constant). Otherwise, it creates and returns a new {@link ConstraintChecker}
     * with the processed schema descriptors.
     *
     * @param storageReader The storage reader from which constraints are to be fetched and
     * processed.
     * @return A {@link ConstraintChecker} instance based on the provided storage reader's
     * constraints, or the EMPTY_CHECKER if no relevant constraints are found.
     */
    public static final Function<StorageReader, ConstraintChecker> STORAGE_READER_CONSTRAINT_BUILDER =
            storageReader -> {
                var nodeLabelSchemaDescriptors = new ArrayList<LabelSchemaDescriptor>();
                var relsLabelSchemaDescriptors = new ArrayList<RelationTypeSchemaDescriptor>();

                for (Iterator<ConstraintDescriptor> it = storageReader.constraintsGetAll(); it.hasNext(); ) {
                    ConstraintDescriptor constraintDescriptor = it.next();
                    if (constraintDescriptor.enforcesPropertyExistence()) {
                        constraintDescriptor.schema().processWith(new SchemaProcessor() {
                            @Override
                            public void processSpecific(LabelSchemaDescriptor labelSchemaDescriptor) {
                                nodeLabelSchemaDescriptors.add(labelSchemaDescriptor);
                            }

                            @Override
                            public void processSpecific(RelationTypeSchemaDescriptor relationTypeSchemaDescriptor) {
                                relsLabelSchemaDescriptors.add(relationTypeSchemaDescriptor);
                            }

                            @Override
                            public void processSpecific(SchemaDescriptor schemaDescriptor) {
                                throw new UnsupportedOperationException(
                                        "processSpecific for SchemaDescriptor class is not implemented.");
                            }
                        });
                    }
                }

                if (nodeLabelSchemaDescriptors.isEmpty() && relsLabelSchemaDescriptors.isEmpty()) {
                    return EMPTY_CHECKER;
                }

                return new ConstraintChecker(storageReader, nodeLabelSchemaDescriptors, relsLabelSchemaDescriptors);
            };

    private final StorageReader storageReader;
    private final List<LabelSchemaDescriptor> nodeLabelSchemaDescriptors;
    private final List<RelationTypeSchemaDescriptor> relationTypeSchemaDescriptors;
    private final MutableLongObjectMap<int[]> nodePropertyMap = new LongObjectHashMap<>();
    private final MutableLongObjectMap<int[]> relPropertyMap = new LongObjectHashMap<>();

    /**
     * Constructor that initializes the constraint checker with the provided storage reader and schema
     * descriptors.
     *
     * @param storageReader              The storage reader to retrieve schema constraints.
     * @param nodeLabelSchemaDescriptors List of node label schema descriptors.
     * @param relsLabelSchemaDescriptors List of relationship type schema descriptors.
     */
    public ConstraintChecker(
            StorageReader storageReader,
            List<LabelSchemaDescriptor> nodeLabelSchemaDescriptors,
            List<RelationTypeSchemaDescriptor> relsLabelSchemaDescriptors) {
        this.storageReader = storageReader;
        this.nodeLabelSchemaDescriptors = nodeLabelSchemaDescriptors;
        this.relationTypeSchemaDescriptors = relsLabelSchemaDescriptors;

        for (LabelSchemaDescriptor schemaDescriptor : nodeLabelSchemaDescriptors) {
            sync(
                    this.nodePropertyMap,
                    schemaDescriptor.getLabelId(),
                    immutableSorter(schemaDescriptor.getPropertyIds()));
        }

        for (RelationTypeSchemaDescriptor schemaDescriptor : relsLabelSchemaDescriptors) {
            sync(
                    this.relPropertyMap,
                    schemaDescriptor.getRelTypeId(),
                    immutableSorter(schemaDescriptor.getPropertyIds()));
        }
    }

    /**
     * Creates or retrieves an existing ConstraintChecker for the provided storage reader.
     *
     * @param storageReader The storage reader to retrieve schema constraints.
     * @return The constraint checker instance.
     */
    static ConstraintChecker constraintChecker(StorageReader storageReader) {
        return storageReader.getOrCreateSchemaDependantState(
                ConstraintChecker.class, STORAGE_READER_CONSTRAINT_BUILDER);
    }

    // TODO: Make immutable and return a new Property instead of mutating it.
    private static void sync(MutableLongObjectMap<int[]> propMap, int typeId, int[] propertyIds) {
        int[] current = propMap.get(typeId);
        if (current != null) {
            propertyIds = PrimitiveArrays.union(current, propertyIds);
        }

        propMap.put(typeId, propertyIds);
    }

    private static int[] immutableSorter(int[] propertyIds) {
        return Arrays.stream(propertyIds).sorted().toArray();
    }

    private static boolean found(int valCheck, int[] array) {

        return Arrays.stream(array).anyMatch(val -> val == valCheck);
    }

    public StorageReader getStorageReader() {
        return storageReader;
    }

    public List<LabelSchemaDescriptor> getNodeLabelSchemaDescriptors() {
        return nodeLabelSchemaDescriptors;
    }

    public List<RelationTypeSchemaDescriptor> getRelationTypeSchemaDescriptors() {
        return relationTypeSchemaDescriptors;
    }

    public MutableLongObjectMap<int[]> getNodePropertyMap() {
        return nodePropertyMap;
    }

    public MutableLongObjectMap<int[]> getRelPropertyMap() {
        return relPropertyMap;
    }

    /**
     * Visits the transaction state to delegate constraint checking tasks.
     *
     * @param cursorContext  The context for the cursor operations.
     * @param cursorFactory  The factory to create cursors.
     * @param memoryTracker  The memory tracker instance.
     * @param read           Read operations.
     * @param txStateVisitor The transaction state visitor.
     * @return An instance of the transaction state visitor.
     */
    TxStateVisitor visit(
            CursorContext cursorContext,
            CursorFactory cursorFactory,
            MemoryTracker memoryTracker,
            Read read,
            TxStateVisitor txStateVisitor) {
        return new ConstraintDelegator(this, cursorContext, cursorFactory, memoryTracker, txStateVisitor, read);
    }

    /**
     * Checks if a node satisfies the schema constraints.
     *
     * @param nodeId       The ID of the node.
     * @param tokenSet     The set of tokens associated with the node.
     * @param propsToCheck Properties to check against the schema constraints.
     * @throws NodePropertyExistenceException If a property existence constraint is violated.
     */
    public void checkNode(long nodeId, TokenSet tokenSet, IntSet propsToCheck) throws NodePropertyExistenceException {
        int numberOfLabels = tokenSet.numberOfTokens();
        long tsType;
        if (numberOfLabels > this.nodePropertyMap.size()) {
            MutableLongIterator labels = this.nodePropertyMap.keySet().longIterator();

            while (labels.hasNext()) {
                tsType = labels.next();
                if (tokenSet.contains(Math.toIntExact(tsType))) {
                    this.checkNode(nodeId, tsType, this.nodePropertyMap.get(tsType), propsToCheck);
                }
            }
        } else {
            for (int i = 0; i < numberOfLabels; ++i) {
                tsType = tokenSet.token(i);
                int[] pks = this.nodePropertyMap.get(tsType);
                if (pks != null) {
                    this.checkNode(nodeId, tsType, pks, propsToCheck);
                }
            }
        }
    }

    private void checkNode(long nodeId, long typeKey, int[] requiredKeys, IntSet propsToCheck)
            throws NodePropertyExistenceException {

        for (int key : requiredKeys) {
            if (!propsToCheck.contains(key)) {
                this.nodeConstraintFailure(nodeId, typeKey, key);
            }
        }
    }

    /**
     * Handles the failure of a node constraint. The method checks the property existence constraints
     * associated with the provided node label. If any constraints are violated, it throws an
     * appropriate exception indicating the failure.
     *
     * @param nodeId      The ID of the node that failed the constraint.
     * @param label       The label of the node that failed the constraint.
     * @param propertyKey The property key that triggered the constraint failure.
     * @throws NodePropertyExistenceException When a constraint violation is detected.
     */
    private void nodeConstraintFailure(long nodeId, long label, int propertyKey) throws NodePropertyExistenceException {
        // Iterate over the node label schema descriptors
        for (LabelSchemaDescriptor labelSchemaDescriptor : nodeLabelSchemaDescriptors) {
            if (labelSchemaDescriptor.getLabelId() == label
                    && found(propertyKey, labelSchemaDescriptor.getPropertyIds())) {
                // When a match is found, fetch constraints for the schema
                Iterator<ConstraintDescriptor> constraintDescriptorIterator =
                        storageReader.constraintsGetForSchema(labelSchemaDescriptor);

                // Check if any of the fetched constraints enforce property existence
                while (constraintDescriptorIterator.hasNext()) {
                    ConstraintDescriptor constraintDescriptor = constraintDescriptorIterator.next();

                    if (constraintDescriptor.enforcesPropertyExistence()) {
                        throw new NodePropertyExistenceException(
                                labelSchemaDescriptor, Phase.VALIDATION, nodeId, storageReader.tokenNameLookup());
                    }
                }

                // If we've reached this point, then no constraint enforces property existence for this schema, so we
                // can exit the method.
                return;
            }
        }

        // If the method hasn't returned by now, then the node's label doesn't match any of the schema descriptors,
        // indicating a missing node type.
        throw new IllegalStateException(String.format("%d is missing from Node type (%d)", propertyKey, label));
    }

    /**
     * Handles the failure of a relationship constraint. The method checks the property existence
     * constraints associated with the provided relationship type. If any constraints are violated, it
     * throws an appropriate exception indicating the failure.
     *
     * @param relId            The ID of the relationship that failed the constraint.
     * @param relationshipType The type of the relationship that failed the constraint.
     * @param propertyKey      The property key that triggered the constraint failure.
     * @throws RelationshipPropertyExistenceException When a constraint violation is detected.
     */
    public void relConstraintFailure(long relId, int relationshipType, int propertyKey)
            throws RelationshipPropertyExistenceException {
        // Iterate over the relation type schema descriptors
        for (RelationTypeSchemaDescriptor relationTypeSchemaDescriptor : relationTypeSchemaDescriptors) {
            if (relationTypeSchemaDescriptor.getRelTypeId() == relationshipType
                    && found(propertyKey, relationTypeSchemaDescriptor.getPropertyIds())) {
                // When a match is found, fetch constraints for the schema
                Iterator<ConstraintDescriptor> constraintDescriptorIterator =
                        storageReader.constraintsGetForSchema(relationTypeSchemaDescriptor);

                // Check if any of the fetched constraints enforce property existence
                while (constraintDescriptorIterator.hasNext()) {
                    ConstraintDescriptor constraintDescriptor = constraintDescriptorIterator.next();

                    if (constraintDescriptor.enforcesPropertyExistence()) {
                        throw new RelationshipPropertyExistenceException(
                                relationTypeSchemaDescriptor, Phase.VALIDATION, relId, storageReader.tokenNameLookup());
                    }
                }

                // If we've reached this point, then no constraint enforces property existence for this schema, so we
                // can exit the method.
                return;
            }
        }

        // If the method hasn't returned by now, then the relationship's type doesn't match any of the schema
        // descriptors, indicating a missing relationship type.
        throw new IllegalStateException(String.format(
                "Missing property: %d for %d. Check your constraint configurations.", propertyKey, relationshipType));
    }
}
