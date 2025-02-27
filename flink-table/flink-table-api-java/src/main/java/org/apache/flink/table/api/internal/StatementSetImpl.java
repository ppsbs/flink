/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.api.internal;

import org.apache.flink.annotation.Experimental;
import org.apache.flink.annotation.Internal;
import org.apache.flink.table.api.CompiledPlan;
import org.apache.flink.table.api.ExplainDetail;
import org.apache.flink.table.api.StatementSet;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableDescriptor;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.catalog.ContextResolvedTable;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.catalog.ResolvedCatalogTable;
import org.apache.flink.table.catalog.SchemaTranslator;
import org.apache.flink.table.catalog.UnresolvedIdentifier;
import org.apache.flink.table.operations.ModifyOperation;
import org.apache.flink.table.operations.Operation;
import org.apache.flink.table.operations.SinkModifyOperation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** Implementation for {@link StatementSet}. */
@Internal
public class StatementSetImpl<E extends TableEnvironmentInternal> implements StatementSet {
    protected final E tableEnvironment;
    protected final List<ModifyOperation> operations = new ArrayList<>();

    protected StatementSetImpl(E tableEnvironment) {
        this.tableEnvironment = tableEnvironment;
    }

    @Override
    public StatementSet addInsertSql(String statement) {
        List<Operation> operations = tableEnvironment.getParser().parse(statement);

        if (operations.size() != 1) {
            throw new TableException("Only single statement is supported.");
        }

        Operation operation = operations.get(0);
        if (operation instanceof ModifyOperation) {
            this.operations.add((ModifyOperation) operation);
        } else {
            throw new TableException("Only insert statement is supported now.");
        }
        return this;
    }

    @Override
    public StatementSet addInsert(String targetPath, Table table) {
        return addInsert(targetPath, table, false);
    }

    @Override
    public StatementSet addInsert(String targetPath, Table table, boolean overwrite) {
        UnresolvedIdentifier unresolvedIdentifier =
                tableEnvironment.getParser().parseIdentifier(targetPath);
        ObjectIdentifier objectIdentifier =
                tableEnvironment.getCatalogManager().qualifyIdentifier(unresolvedIdentifier);
        ContextResolvedTable contextResolvedTable =
                tableEnvironment.getCatalogManager().getTableOrError(objectIdentifier);

        operations.add(
                new SinkModifyOperation(
                        contextResolvedTable,
                        table.getQueryOperation(),
                        Collections.emptyMap(),
                        overwrite,
                        Collections.emptyMap()));

        return this;
    }

    @Override
    public StatementSet addInsert(TableDescriptor targetDescriptor, Table table) {
        return addInsert(targetDescriptor, table, false);
    }

    @Override
    public StatementSet addInsert(
            TableDescriptor targetDescriptor, Table table, boolean overwrite) {
        final SchemaTranslator.ConsumingResult schemaTranslationResult =
                SchemaTranslator.createConsumingResult(
                        tableEnvironment.getCatalogManager().getDataTypeFactory(),
                        table.getResolvedSchema().toSourceRowDataType(),
                        targetDescriptor.getSchema().orElse(null),
                        false);
        final TableDescriptor updatedDescriptor =
                targetDescriptor.toBuilder().schema(schemaTranslationResult.getSchema()).build();

        final ResolvedCatalogTable resolvedCatalogBaseTable =
                tableEnvironment
                        .getCatalogManager()
                        .resolveCatalogTable(updatedDescriptor.toCatalogTable());

        operations.add(
                new SinkModifyOperation(
                        ContextResolvedTable.anonymous(resolvedCatalogBaseTable),
                        table.getQueryOperation(),
                        Collections.emptyMap(),
                        overwrite,
                        Collections.emptyMap()));

        return this;
    }

    @Override
    public String explain(ExplainDetail... extraDetails) {
        List<Operation> operationList =
                operations.stream().map(o -> (Operation) o).collect(Collectors.toList());
        return tableEnvironment.explainInternal(operationList, extraDetails);
    }

    @Override
    public TableResult execute() {
        try {
            return tableEnvironment.executeInternal(operations);
        } finally {
            operations.clear();
        }
    }

    /**
     * Get the {@link CompiledPlan} of the all statements and Tables as a batch.
     *
     * <p>The added statements and Tables will NOT be cleared when executing this method.
     *
     * <p><b>Note:</b> This API is <b>experimental</b> and subject to change in future releases.
     *
     * @return the string json representation of an optimized ExecNode plan for the statements and
     *     Tables.
     */
    @Experimental
    public CompiledPlan compilePlan() {
        return tableEnvironment.compilePlan(operations);
    }
}
