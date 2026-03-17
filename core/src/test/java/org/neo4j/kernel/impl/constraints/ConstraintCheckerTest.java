/*
 * Copyright (c) DozerDB
 * ALL RIGHTS RESERVED.
 *
 * DozerDb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.neo4j.kernel.impl.constraints;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.neo4j.kernel.impl.constraints.ConstraintChecker.EMPTY_CHECKER;
import static org.neo4j.kernel.impl.constraints.ConstraintChecker.STORAGE_READER_CONSTRAINT_BUILDER;

import java.util.Collections;
import java.util.List;
import org.eclipse.collections.api.set.primitive.IntSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.internal.kernel.api.TokenSet;
import org.neo4j.internal.schema.*;
import org.neo4j.kernel.api.exceptions.schema.NodePropertyExistenceException;
import org.neo4j.storageengine.api.StorageReader;

public class ConstraintCheckerTest {

    private StorageReader storageReaderMock;

    @BeforeEach
    public void setUp() {
        storageReaderMock = mock(StorageReader.class);
    }

    @Test
    public void testStorageReaderConstraintBuilder_EmptyConstraints() {
        when(storageReaderMock.constraintsGetAll()).thenReturn(Collections.emptyIterator());

        ConstraintChecker result = STORAGE_READER_CONSTRAINT_BUILDER.apply(storageReaderMock);

        // Check if it returns the empty checker when there are no constraints
        assertSame(EMPTY_CHECKER, result);
    }

    @Test
    public void testCheckNode_NoViolations() throws NodePropertyExistenceException {
        ConstraintChecker checker =
                new ConstraintChecker(storageReaderMock, Collections.emptyList(), Collections.emptyList());

        // Ideally, no exception should be thrown if no constraint is violated
        assertDoesNotThrow(() -> checker.checkNode(1, mock(TokenSet.class), mock(IntSet.class)));
    }

    @Test
    public void testStorageReaderConstraintBuilder_NodePropertyExistenceConstraint() {
        // Regression test: STORAGE_READER_CONSTRAINT_BUILDER previously cast constraintDescriptor
        // directly to LabelSchemaDescriptor instead of constraintDescriptor.schema(), causing
        // a ClassCastException at commit time for databases with property existence constraints.
        LabelSchemaDescriptor labelSchema = mock(LabelSchemaDescriptor.class);
        when(labelSchema.isSchemaDescriptorType(LabelSchemaDescriptor.class)).thenReturn(true);
        when(labelSchema.isSchemaDescriptorType(RelationTypeSchemaDescriptor.class)).thenReturn(false);
        when(labelSchema.getLabelId()).thenReturn(1);
        when(labelSchema.getPropertyIds()).thenReturn(new int[] {10});

        ConstraintDescriptor constraintDescriptor = mock(ConstraintDescriptor.class);
        when(constraintDescriptor.enforcesPropertyExistence()).thenReturn(true);
        when(constraintDescriptor.schema()).thenReturn(labelSchema);

        when(storageReaderMock.constraintsGetAll()).thenReturn(List.of(constraintDescriptor).iterator());

        ConstraintChecker result = assertDoesNotThrow(() -> STORAGE_READER_CONSTRAINT_BUILDER.apply(storageReaderMock));

        assertNotSame(EMPTY_CHECKER, result);
        assertEquals(1, result.getNodeLabelSchemaDescriptors().size());
        assertSame(labelSchema, result.getNodeLabelSchemaDescriptors().get(0));
        assertTrue(result.getRelationTypeSchemaDescriptors().isEmpty());
    }

    @Test
    public void testStorageReaderConstraintBuilder_RelPropertyExistenceConstraint() {
        RelationTypeSchemaDescriptor relSchema = mock(RelationTypeSchemaDescriptor.class);
        when(relSchema.isSchemaDescriptorType(LabelSchemaDescriptor.class)).thenReturn(false);
        when(relSchema.isSchemaDescriptorType(RelationTypeSchemaDescriptor.class)).thenReturn(true);
        when(relSchema.getRelTypeId()).thenReturn(2);
        when(relSchema.getPropertyIds()).thenReturn(new int[] {20});

        ConstraintDescriptor constraintDescriptor = mock(ConstraintDescriptor.class);
        when(constraintDescriptor.enforcesPropertyExistence()).thenReturn(true);
        when(constraintDescriptor.schema()).thenReturn(relSchema);

        when(storageReaderMock.constraintsGetAll()).thenReturn(List.of(constraintDescriptor).iterator());

        ConstraintChecker result = assertDoesNotThrow(() -> STORAGE_READER_CONSTRAINT_BUILDER.apply(storageReaderMock));

        assertNotSame(EMPTY_CHECKER, result);
        assertTrue(result.getNodeLabelSchemaDescriptors().isEmpty());
        assertEquals(1, result.getRelationTypeSchemaDescriptors().size());
        assertSame(relSchema, result.getRelationTypeSchemaDescriptors().get(0));
    }
}
