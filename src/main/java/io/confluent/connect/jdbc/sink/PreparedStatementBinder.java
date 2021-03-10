/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.connect.jdbc.sink;

import io.confluent.connect.jdbc.dialect.GenericDatabaseDialect;
import org.apache.kafka.connect.data.*;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.sink.SinkRecord;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import io.confluent.connect.jdbc.dialect.DatabaseDialect;
import io.confluent.connect.jdbc.dialect.DatabaseDialect.StatementBinder;
import io.confluent.connect.jdbc.sink.metadata.FieldsMetadata;
import io.confluent.connect.jdbc.sink.metadata.SchemaPair;

import static java.util.Objects.isNull;

public class PreparedStatementBinder implements StatementBinder {

  private final JdbcSinkConfig.PrimaryKeyMode pkMode;
  private final PreparedStatement statement;
  private final SchemaPair schemaPair;
  private final FieldsMetadata fieldsMetadata;
  private final JdbcSinkConfig.InsertMode insertMode;
  private final DatabaseDialect dialect;
  private final Set<String> enumSets;
  private final Schema enumSetSchema = SchemaBuilder.array(Schema.STRING_SCHEMA)
          .parameter("isEnumSet", "true").build();
  private final JdbcSinkConfig config;

  public PreparedStatementBinder(
      DatabaseDialect dialect,
      PreparedStatement statement,
      JdbcSinkConfig.PrimaryKeyMode pkMode,
      SchemaPair schemaPair,
      FieldsMetadata fieldsMetadata,
      JdbcSinkConfig.InsertMode insertMode
  ) {
    this.dialect = dialect;
    this.pkMode = pkMode;
    this.statement = statement;
    this.schemaPair = schemaPair;
    this.fieldsMetadata = fieldsMetadata;
    this.insertMode = insertMode;
    if (dialect instanceof GenericDatabaseDialect) {
      GenericDatabaseDialect gdialect = (GenericDatabaseDialect) dialect;
      if (gdialect.config instanceof JdbcSinkConfig) {
        config = (JdbcSinkConfig) gdialect.config;
        enumSets = new HashSet<>(config.enumSets);
      } else {
        config = null;
        enumSets = new HashSet<>();
      }
    } else {
      config = null;
      enumSets = new HashSet<>();
    }
  }

  @Override
  public void bindRecord(SinkRecord record) throws SQLException {
    final Struct valueStruct = (Struct) record.value();
    boolean isDelete = isNull(valueStruct);
    // Assumption: the relevant SQL has placeholders for keyFieldNames first followed by
    //             nonKeyFieldNames, in iteration order for all INSERT/ UPSERT queries
    //             the relevant SQL has placeholders for keyFieldNames,
    //             in iteration order for all DELETE queries
    //             the relevant SQL has placeholders for nonKeyFieldNames first followed by
    //             keyFieldNames, in iteration order for all UPDATE queries

    int index = 1;
    if (!isDelete && config != null && config.deleteByField) {
      Field field = record.valueSchema().field("deleted");
      if (field != null && field.schema().type() == Schema.Type.BOOLEAN) {
        isDelete = valueStruct.getBoolean("deleted");
      }
    }
    if (isDelete) {
      bindKeyFields(record, index);
    } else {
      switch (insertMode) {
        case INSERT:
        case UPSERT:
          index = bindKeyFields(record, index);
          bindNonKeyFields(record, valueStruct, index);
          break;

        case UPDATE:
          index = bindNonKeyFields(record, valueStruct, index);
          bindKeyFields(record, index);
          break;
        default:
          throw new AssertionError();

      }
    }
    statement.addBatch();
  }

  protected int bindKeyFields(SinkRecord record, int index) throws SQLException {
    switch (pkMode) {
      case NONE:
        if (!fieldsMetadata.keyFieldNames.isEmpty()) {
          throw new AssertionError();
        }
        break;

      case KAFKA: {
        assert fieldsMetadata.keyFieldNames.size() == 3;
        bindField(index++, Schema.STRING_SCHEMA, record.topic());
        bindField(index++, Schema.INT32_SCHEMA, record.kafkaPartition());
        bindField(index++, Schema.INT64_SCHEMA, record.kafkaOffset());
      }
      break;

      case RECORD_KEY: {
        if (schemaPair.keySchema.type().isPrimitive()) {
          assert fieldsMetadata.keyFieldNames.size() == 1;
          bindField(index++, schemaPair.keySchema, record.key());
        } else {
          for (String fieldName : fieldsMetadata.keyFieldNames) {
            final Field field = schemaPair.keySchema.field(fieldName);
            bindField(index++, field.schema(), ((Struct) record.key()).get(field));
          }
        }
      }
      break;

      case RECORD_VALUE: {
        for (String fieldName : fieldsMetadata.keyFieldNames) {
          final Field field = schemaPair.valueSchema.field(fieldName);
          bindField(index++, field.schema(), ((Struct) record.value()).get(field));
        }
      }
      break;

      default:
        throw new ConnectException("Unknown primary key mode: " + pkMode);
    }
    return index;
  }

  protected int bindNonKeyFields(
      SinkRecord record,
      Struct valueStruct,
      int index
  ) throws SQLException {
    for (final String fieldName : fieldsMetadata.nonKeyFieldNames) {
      final Field field = record.valueSchema().field(fieldName);
      Schema schema;
      if (enumSets.contains(fieldName)) {
        schema = enumSetSchema;
      } else {
        schema = field.schema();
      }
      bindField(index++, field.schema(), valueStruct.get(field));
    }
    return index;
  }

  protected void bindField(int index, Schema schema, Object value) throws SQLException {
    dialect.bindField(statement, index, schema, value);
  }
}
