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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.neo4j.kernel.impl.constraints.ConstraintChecker.EMPTY_CHECKER;
import static org.neo4j.kernel.impl.constraints.ConstraintChecker.STORAGE_READER_CONSTRAINT_BUILDER;

import java.util.Collections;
import java.util.List;
import org.eclipse.collections.impl.factory.primitive.IntSets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.common.TokenNameLookup;
import org.neo4j.internal.kernel.api.TokenSet;
import org.neo4j.internal.schema.*;
import org.neo4j.internal.schema.constraints.ConstraintDescriptorFactory;
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

        assertThat(result).isSameAs(EMPTY_CHECKER);
    }

    @Test
    public void testStorageReaderConstraintBuilder_SkipsNonExistenceConstraints() {
        ConstraintDescriptor constraintDescriptor = mock(ConstraintDescriptor.class);
        when(constraintDescriptor.enforcesPropertyExistence()).thenReturn(false);

        when(storageReaderMock.constraintsGetAll())
                .thenReturn(List.of(constraintDescriptor).iterator());

        ConstraintChecker result = STORAGE_READER_CONSTRAINT_BUILDER.apply(storageReaderMock);

        assertThat(result).isSameAs(EMPTY_CHECKER);
    }

    @Test
    public void testStorageReaderConstraintBuilder_NodePropertyExistenceConstraint() {
        // Regression test: STORAGE_READER_CONSTRAINT_BUILDER previously cast constraintDescriptor
        // directly to LabelSchemaDescriptor instead of constraintDescriptor.schema(), causing
        // a ClassCastException at commit time for databases with property existence constraints.
        int labelId = 1;
        int propertyId = 10;
        LabelSchemaDescriptor labelSchema = SchemaDescriptors.forLabel(labelId, propertyId);
        ConstraintDescriptor constraint = ConstraintDescriptorFactory.existsForSchema(labelSchema, false);

        when(storageReaderMock.constraintsGetAll())
                .thenReturn(List.of(constraint).iterator());

        ConstraintChecker result = STORAGE_READER_CONSTRAINT_BUILDER.apply(storageReaderMock);

        assertThat(result).isNotSameAs(EMPTY_CHECKER);
        assertThat(result.getNodeLabelSchemaDescriptors()).hasSize(1);
        assertThat(result.getNodeLabelSchemaDescriptors().get(0).getLabelId()).isEqualTo(labelId);
        assertThat(result.getNodeLabelSchemaDescriptors().get(0).getPropertyIds())
                .containsExactly(propertyId);
        assertThat(result.getRelationTypeSchemaDescriptors()).isEmpty();
        assertThat(result.getNodePropertyMap().containsKey(labelId)).isTrue();
        assertThat(result.getNodePropertyMap().get(labelId)).containsExactly(propertyId);
    }

    @Test
    public void testStorageReaderConstraintBuilder_RelPropertyExistenceConstraint() {
        int relTypeId = 2;
        int propertyId = 20;
        RelationTypeSchemaDescriptor relSchema = SchemaDescriptors.forRelType(relTypeId, propertyId);
        ConstraintDescriptor constraint = ConstraintDescriptorFactory.existsForSchema(relSchema, false);

        when(storageReaderMock.constraintsGetAll())
                .thenReturn(List.of(constraint).iterator());

        ConstraintChecker result = STORAGE_READER_CONSTRAINT_BUILDER.apply(storageReaderMock);

        assertThat(result).isNotSameAs(EMPTY_CHECKER);
        assertThat(result.getNodeLabelSchemaDescriptors()).isEmpty();
        assertThat(result.getRelationTypeSchemaDescriptors()).hasSize(1);
        assertThat(result.getRelationTypeSchemaDescriptors().get(0).getRelTypeId())
                .isEqualTo(relTypeId);
        assertThat(result.getRelationTypeSchemaDescriptors().get(0).getPropertyIds())
                .containsExactly(propertyId);
        assertThat(result.getRelPropertyMap().containsKey(relTypeId)).isTrue();
        assertThat(result.getRelPropertyMap().get(relTypeId)).containsExactly(propertyId);
    }

    @Test
    public void testCheckNode_PassesWhenRequiredPropertyPresent() throws NodePropertyExistenceException {
        int labelId = 5;
        int requiredPropertyId = 42;
        LabelSchemaDescriptor labelSchema = SchemaDescriptors.forLabel(labelId, requiredPropertyId);

        ConstraintChecker checker =
                new ConstraintChecker(storageReaderMock, List.of(labelSchema), Collections.emptyList());

        TokenSet tokenSet = mock(TokenSet.class);
        when(tokenSet.numberOfTokens()).thenReturn(1);
        when(tokenSet.token(0)).thenReturn(labelId);

        checker.checkNode(1L, tokenSet, IntSets.immutable.of(requiredPropertyId));
    }

    @Test
    public void testCheckNode_DetectsViolationForMissingRequiredProperty() {
        int labelId = 5;
        int requiredPropertyId = 42;
        LabelSchemaDescriptor labelSchema = SchemaDescriptors.forLabel(labelId, requiredPropertyId);
        ConstraintDescriptor constraint = ConstraintDescriptorFactory.existsForSchema(labelSchema, false);

        TokenNameLookup tokenNameLookup = mock(TokenNameLookup.class);
        when(tokenNameLookup.labelGetName(labelId)).thenReturn("TestLabel");
        when(tokenNameLookup.propertyKeyGetName(requiredPropertyId)).thenReturn("requiredProp");
        when(storageReaderMock.constraintsGetForSchema(labelSchema))
                .thenReturn(List.of(constraint).iterator());
        when(storageReaderMock.tokenNameLookup()).thenReturn(tokenNameLookup);

        ConstraintChecker checker =
                new ConstraintChecker(storageReaderMock, List.of(labelSchema), Collections.emptyList());

        TokenSet tokenSet = mock(TokenSet.class);
        when(tokenSet.numberOfTokens()).thenReturn(1);
        when(tokenSet.token(0)).thenReturn(labelId);

        assertThatThrownBy(() -> checker.checkNode(1L, tokenSet, IntSets.immutable.empty()))
                .isInstanceOf(NodePropertyExistenceException.class);
    }

    @Test
    public void testCheckNode_NoViolationsWhenLabelDoesNotMatch() throws NodePropertyExistenceException {
        int constrainedLabelId = 5;
        int requiredPropertyId = 42;
        LabelSchemaDescriptor labelSchema = SchemaDescriptors.forLabel(constrainedLabelId, requiredPropertyId);

        ConstraintChecker checker =
                new ConstraintChecker(storageReaderMock, List.of(labelSchema), Collections.emptyList());

        // Node has a different label (99), so the constraint on label 5 should not apply
        TokenSet tokenSet = mock(TokenSet.class);
        when(tokenSet.numberOfTokens()).thenReturn(1);
        when(tokenSet.token(0)).thenReturn(99);

        checker.checkNode(1L, tokenSet, IntSets.immutable.empty());
    }
}
