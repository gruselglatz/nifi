/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.standard.db.impl;

import com.google.common.base.Preconditions;
import org.apache.nifi.processors.standard.db.DatabaseAdapter;
import org.apache.nifi.util.StringUtils;

import java.util.Collection;
import java.util.List;

/**
 * An Apache Phoenix database adapter that generates ANSI SQL.
 */
public final class PhoenixDatabaseAdapter implements DatabaseAdapter {
    public static final String NAME = "Phoenix";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Generates Phoenix compliant SQL";
    }

    @Override
    public String getSelectStatement(String tableName, String columnNames, String whereClause, String orderByClause, Long limit, Long offset) {
        return getSelectStatement(tableName, columnNames, whereClause, orderByClause, limit, offset, null);
    }

    @Override
    public String getSelectStatement(String tableName, String columnNames, String whereClause, String orderByClause, Long limit, Long offset, String columnForPartitioning) {
        if (StringUtils.isEmpty(tableName)) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }
        final StringBuilder query = new StringBuilder("SELECT ");
        if (StringUtils.isEmpty(columnNames) || columnNames.trim().equals("*")) {
            query.append("*");
        } else {
            query.append(columnNames);
        }
        query.append(" FROM ");
        query.append(tableName);

        if (!StringUtils.isEmpty(whereClause)) {
            query.append(" WHERE ");
            query.append(whereClause);
            if (!StringUtils.isEmpty(columnForPartitioning)) {
                query.append(" AND ");
                query.append(columnForPartitioning);
                query.append(" >= ");
                query.append(offset != null ? offset : "0");
                if (limit != null) {
                    query.append(" AND ");
                    query.append(columnForPartitioning);
                    query.append(" < ");
                    query.append((offset == null ? 0 : offset) + limit);
                }
            }
        }
        if (!StringUtils.isEmpty(orderByClause) && StringUtils.isEmpty(columnForPartitioning)) {
            query.append(" ORDER BY ");
            query.append(orderByClause);
        }

        if (StringUtils.isEmpty(columnForPartitioning)) {
            if (limit != null) {
                query.append(" LIMIT ");
                query.append(limit);
            }
            if (offset != null && offset > 0) {
                query.append(" OFFSET ");
                query.append(offset);
            }
        }
        return query.toString();
    }

    @Override
    public boolean supportsUpsert() {
        return true;
    }

    @Override
    public String getUpsertStatement(String tableName, List<String> columnNames, Collection<String> uniqueKeyColumnNames) {
        Preconditions.checkArgument(!StringUtils.isEmpty(tableName), "Table name cannot be null or blank");
        Preconditions.checkArgument(columnNames != null && !columnNames.isEmpty(), "Column names cannot be null or empty");

        final StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("UPSERT INTO ");
        sqlBuilder.append(tableName);
        sqlBuilder.append(" (");
        sqlBuilder.append(String.join(", ", columnNames));
        sqlBuilder.append(") VALUES (");
        sqlBuilder.append(StringUtils.repeat("?", ", ", columnNames.size()));
        sqlBuilder.append(")");
        return sqlBuilder.toString();
    }
}
