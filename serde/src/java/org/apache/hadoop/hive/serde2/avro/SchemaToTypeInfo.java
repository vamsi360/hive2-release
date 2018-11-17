/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.serde2.avro;

import static org.apache.avro.Schema.Type.BOOLEAN;
import static org.apache.avro.Schema.Type.BYTES;
import static org.apache.avro.Schema.Type.DOUBLE;
import static org.apache.avro.Schema.Type.FIXED;
import static org.apache.avro.Schema.Type.FLOAT;
import static org.apache.avro.Schema.Type.INT;
import static org.apache.avro.Schema.Type.LONG;
import static org.apache.avro.Schema.Type.NULL;
import static org.apache.avro.Schema.Type.STRING;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.avro.Schema;
import org.apache.hadoop.hive.serde2.typeinfo.*;

/**
 * Convert an Avro Schema to a Hive TypeInfo
 */
class SchemaToTypeInfo {
  // Conversion of Avro primitive types to Hive primitive types
  // Avro             Hive
  // Null
  // boolean          boolean    check
  // int              int        check
  // long             bigint     check
  // float            double     check
  // double           double     check
  // bytes            binary     check
  // fixed            binary     check
  // string           string     check
  //                  tinyint
  //                  smallint

  // Map of Avro's primitive types to Hives (for those that are supported by both)
  private static final Map<Schema.Type, TypeInfo> primitiveTypeToTypeInfo = initTypeMap();
  private static Map<Schema.Type, TypeInfo> initTypeMap() {
    Map<Schema.Type, TypeInfo> theMap = new Hashtable<Schema.Type, TypeInfo>();
    theMap.put(NULL, TypeInfoFactory.getPrimitiveTypeInfo("void"));
    theMap.put(BOOLEAN, TypeInfoFactory.getPrimitiveTypeInfo("boolean"));
    theMap.put(INT, TypeInfoFactory.getPrimitiveTypeInfo("int"));
    theMap.put(LONG, TypeInfoFactory.getPrimitiveTypeInfo("bigint"));
    theMap.put(FLOAT, TypeInfoFactory.getPrimitiveTypeInfo("float"));
    theMap.put(DOUBLE, TypeInfoFactory.getPrimitiveTypeInfo("double"));
    theMap.put(BYTES, TypeInfoFactory.getPrimitiveTypeInfo("binary"));
    theMap.put(FIXED, TypeInfoFactory.getPrimitiveTypeInfo("binary"));
    theMap.put(STRING, TypeInfoFactory.getPrimitiveTypeInfo("string"));
    return Collections.unmodifiableMap(theMap);
  }

  /**
   * Generate a list of of TypeInfos from an Avro schema.  This method is
   * currently public due to some weirdness in deserializing unions, but
   * will be made private once that is resolved.
   * @param schema Schema to generate field types for
   * @return List of TypeInfos, each element of which is a TypeInfo derived
   *         from the schema.
   * @throws AvroSerdeException for problems during conversion.
   */
  public static List<TypeInfo> generateColumnTypes(Schema schema) throws AvroSerdeException {
    return generateColumnTypes (schema, null);
  }

  /**
   * Generate a list of of TypeInfos from an Avro schema.  This method is
   * currently public due to some weirdness in deserializing unions, but
   * will be made private once that is resolved.
   * @param schema Schema to generate field types for
   * @param seenSchemas stores schemas processed in the parsing done so far,
   *         helping to resolve circular references in the schema
   * @return List of TypeInfos, each element of which is a TypeInfo derived
   *         from the schema.
   * @throws AvroSerdeException for problems during conversion.
   */
  public static List<TypeInfo> generateColumnTypes(Schema schema,
      Set<Schema> seenSchemas) throws AvroSerdeException {
    List<Schema.Field> fields = schema.getFields();

    List<TypeInfo> types = new ArrayList<TypeInfo>(fields.size());

    for (Schema.Field field : fields) {
      types.add(generateTypeInfo(field.schema(), seenSchemas));
    }

    return types;
  }

  static InstanceCache<Schema, TypeInfo> typeInfoCache = new InstanceCache<Schema, TypeInfo>() {
                                  @Override
                                  protected TypeInfo makeInstance(Schema s,
                                      Set<Schema> seenSchemas)
                                      throws AvroSerdeException {
                                    return generateTypeInfoWorker(s, seenSchemas);
                                  }
                                };
  /**
   * Convert an Avro Schema into an equivalent Hive TypeInfo.
   * @param schema to record. Must be of record type.
   * @param seenSchemas stores schemas processed in the parsing done so far,
   *         helping to resolve circular references in the schema
   * @return TypeInfo matching the Avro schema
   * @throws AvroSerdeException for any problems during conversion.
   */
  public static TypeInfo generateTypeInfo(Schema schema,
      Set<Schema> seenSchemas) throws AvroSerdeException {
    // For bytes type, it can be mapped to decimal.
    Schema.Type type = schema.getType();
    if (type == BYTES && AvroSerDe.DECIMAL_TYPE_NAME
      .equalsIgnoreCase(schema.getProp(AvroSerDe.AVRO_PROP_LOGICAL_TYPE))) {
      int precision = 0;
      int scale = 0;
      try {
        precision = schema.getJsonProp(AvroSerDe.AVRO_PROP_PRECISION).getIntValue();
        scale = schema.getJsonProp(AvroSerDe.AVRO_PROP_SCALE).getIntValue();
      } catch (Exception ex) {
        throw new AvroSerdeException("Failed to obtain scale value from file schema: " + schema, ex);
      }

      try {
        HiveDecimalUtils.validateParameter(precision, scale);
      } catch (Exception ex) {
        throw new AvroSerdeException("Invalid precision or scale for decimal type", ex);
      }

      return TypeInfoFactory.getDecimalTypeInfo(precision, scale);
    }

    if (type == STRING &&
      AvroSerDe.CHAR_TYPE_NAME.equalsIgnoreCase(schema.getProp(AvroSerDe.AVRO_PROP_LOGICAL_TYPE))) {
      int maxLength = 0;
      try {
        maxLength = schema.getJsonProp(AvroSerDe.AVRO_PROP_MAX_LENGTH).getValueAsInt();
      } catch (Exception ex) {
        throw new AvroSerdeException("Failed to obtain maxLength value from file schema: " + schema, ex);
      }
      return TypeInfoFactory.getCharTypeInfo(maxLength);
    }

    if (type == STRING && AvroSerDe.VARCHAR_TYPE_NAME
      .equalsIgnoreCase(schema.getProp(AvroSerDe.AVRO_PROP_LOGICAL_TYPE))) {
      int maxLength = 0;
      try {
        maxLength = schema.getJsonProp(AvroSerDe.AVRO_PROP_MAX_LENGTH).getValueAsInt();
      } catch (Exception ex) {
        throw new AvroSerdeException("Failed to obtain maxLength value from file schema: " + schema, ex);
      }
      return TypeInfoFactory.getVarcharTypeInfo(maxLength);
    }

    if (type == INT &&
      AvroSerDe.DATE_TYPE_NAME.equals(schema.getProp(AvroSerDe.AVRO_PROP_LOGICAL_TYPE))) {
      return TypeInfoFactory.dateTypeInfo;
    }

    if (type == LONG &&
      AvroSerDe.TIMESTAMP_TYPE_NAME.equals(schema.getProp(AvroSerDe.AVRO_PROP_LOGICAL_TYPE))) {
      return TypeInfoFactory.timestampTypeInfo;
    }

    return typeInfoCache.retrieve(schema, seenSchemas);
  }

  private static TypeInfo generateTypeInfoWorker(Schema schema,
      Set<Schema> seenSchemas) throws AvroSerdeException {
    // Avro requires NULLable types to be defined as unions of some type T
    // and NULL.  This is annoying and we're going to hide it from the user.
    if(AvroSerdeUtils.isNullableType(schema)) {
      return generateTypeInfo(
        AvroSerdeUtils.getOtherTypeFromNullableType(schema), seenSchemas);
    }

    Schema.Type type = schema.getType();
    if(primitiveTypeToTypeInfo.containsKey(type)) {
      return primitiveTypeToTypeInfo.get(type);
    }

    switch(type) {
      case RECORD: return generateRecordTypeInfo(schema, seenSchemas);
      case MAP:    return generateMapTypeInfo(schema, seenSchemas);
      case ARRAY:  return generateArrayTypeInfo(schema, seenSchemas);
      case UNION:  return generateUnionTypeInfo(schema, seenSchemas);
      case ENUM:   return generateEnumTypeInfo(schema);
      case PARAM:  return generateParamTypeInfo(schema, seenSchemas);
      default:     throw new AvroSerdeException("Do not yet support: " + schema);
    }
  }

  private static TypeInfo generateParamTypeInfo(Schema schema, Set<Schema> seenSchemas)
      throws AvroSerdeException {
    assert schema.getType().equals(Schema.Type.PARAM);
    return generateRecordTypeInfo(schema.getValueType(), seenSchemas);
  }

  private static TypeInfo generateRecordTypeInfo(Schema schema,
      Set<Schema> seenSchemas) throws AvroSerdeException {
    assert schema.getType().equals(Schema.Type.RECORD);
    if (seenSchemas == null) {
        seenSchemas = Collections.newSetFromMap(new IdentityHashMap<Schema, Boolean>());
    } else if (seenSchemas.contains(schema)) {// && !typeInfoCache.contains(schema)) {
//      throw new AvroSerdeException(
//          "Recursive schemas are not supported. Recursive schema was " + schema
//              .getFullName());
      return getTypeInfo(schema, seenSchemas);
    }
    seenSchemas.add(schema);
    return getTypeInfo(schema, seenSchemas);
  }

  private static TypeInfo getTypeInfo(Schema schema, Set<Schema> seenSchemas)
      throws AvroSerdeException {
    List<Schema.Field> fields = schema.getFields();
    List<String> fieldNames = new ArrayList<String>(fields.size());
    List<TypeInfo> typeInfos = new ArrayList<TypeInfo>(fields.size());
    List<Schema.Field> recursiveFields = new ArrayList<>();
    int k=0;

    for(int i = 0; i < fields.size(); i++) {
      Schema.Field field = fields.get(i);
      Schema fieldSchema = field.schema();

      if(AvroSerdeUtils.isNullableType(fieldSchema) &&
          schema.equals(AvroSerdeUtils.getOtherTypeFromNullableType(fieldSchema))) {
          recursiveFields.add(field);
      } else {
        fieldNames.add(k, field.name());
        TypeInfo typeInfo = generateTypeInfo(fieldSchema, seenSchemas);
        typeInfos.add(k, typeInfo);
        k++;
      }
    }

    StructTypeInfo structTypeInfo = (StructTypeInfo) TypeInfoFactory.
        getStructTypeInfo(fieldNames, typeInfos);

    if(recursiveFields.size() > 0) {
      ArrayList<String> allStructFieldNames = structTypeInfo.getAllStructFieldNames();
      ArrayList<TypeInfo> allStructFieldTypeInfos = structTypeInfo.getAllStructFieldTypeInfos();

      for (Schema.Field field : recursiveFields) {
        allStructFieldNames.add(field.name());
        allStructFieldTypeInfos.add(structTypeInfo);
      }

      structTypeInfo.setAllStructFieldNames(allStructFieldNames);
      structTypeInfo.setAllStructFieldTypeInfos(allStructFieldTypeInfos);
      structTypeInfo.setRecursiveTypeName(schema.getFullName());
    }
    return structTypeInfo;
  }

  /**
   * Generate a TypeInfo for an Avro Map.  This is made slightly simpler in that
   * Avro only allows maps with strings for keys.
   */
  private static TypeInfo generateMapTypeInfo(Schema schema,
      Set<Schema> seenSchemas) throws AvroSerdeException {
    assert schema.getType().equals(Schema.Type.MAP);
    Schema valueType = schema.getValueType();
    TypeInfo ti = generateTypeInfo(valueType, seenSchemas);

    return TypeInfoFactory.getMapTypeInfo(TypeInfoFactory.getPrimitiveTypeInfo("string"), ti);
  }

  private static TypeInfo generateArrayTypeInfo(Schema schema,
      Set<Schema> seenSchemas) throws AvroSerdeException {
    assert schema.getType().equals(Schema.Type.ARRAY);
    Schema itemsType = schema.getElementType();
    TypeInfo itemsTypeInfo = generateTypeInfo(itemsType, seenSchemas);

    return TypeInfoFactory.getListTypeInfo(itemsTypeInfo);
  }

  private static TypeInfo generateUnionTypeInfo(Schema schema,
      Set<Schema> seenSchemas) throws AvroSerdeException {
    assert schema.getType().equals(Schema.Type.UNION);
    List<Schema> types = schema.getTypes();
    //List<TypeInfo> typeInfos = new ArrayList<TypeInfo>(types.size());
    List<TypeInfo> typeInfos = new ArrayList<TypeInfo>(types.size());
    List<Schema> recursiveType = new ArrayList<>();

    for(Schema type : types) {
      if(seenSchemas != null && seenSchemas.contains(type)) {
        recursiveType.add(type);
      } else {
        typeInfos.add(generateTypeInfo(type, seenSchemas));
      }
    }

    UnionTypeInfo unionTypeInfo = (UnionTypeInfo) TypeInfoFactory.getUnionTypeInfo(typeInfos);

    if(recursiveType.size() > 0) {
      List<TypeInfo> allUnionObjectTypeInfos = unionTypeInfo.getAllUnionObjectTypeInfos();

      ArrayList<TypeInfo> newTypeInfos = new ArrayList<>(allUnionObjectTypeInfos);
      for(Schema type: recursiveType) {
        newTypeInfos.add(unionTypeInfo);
      }

      UnionTypeInfo unionTypeInfo2 = (UnionTypeInfo) TypeInfoFactory.getUnionTypeInfo(newTypeInfos);

      //this is required as we want to set the fully constructed recursive schema as schema for the
      // recursive subtype.
      // TODO: Need to work on an elegant solution for this
      ArrayList<TypeInfo> newTypeInfos2 = new ArrayList<>(newTypeInfos);
      for(TypeInfo typeInfo: newTypeInfos) {
        if(typeInfo.equals(unionTypeInfo)) {
          newTypeInfos2.add(unionTypeInfo2);
        } else {
          newTypeInfos2.add(typeInfo);
        }
      }

      return TypeInfoFactory.getUnionTypeInfo(newTypeInfos2);
    }

    return unionTypeInfo;
  }

  // Hive doesn't have an Enum type, so we're going to treat them as Strings.
  // During the deserialize/serialize stage we'll check for enumness and
  // convert as such.
  private static TypeInfo generateEnumTypeInfo(Schema schema) {
    assert schema.getType().equals(Schema.Type.ENUM);

    return TypeInfoFactory.getPrimitiveTypeInfo("string");
  }
}
