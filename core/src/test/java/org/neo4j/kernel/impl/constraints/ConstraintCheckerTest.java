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
}
