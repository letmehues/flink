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

package org.apache.flink.table.calcite

import org.apache.flink.table.`type`.{ArrayType, DecimalType, InternalType, InternalTypes, MapType, RowType}
import org.apache.flink.table.api.TableException
import org.apache.flink.table.plan.schema.{ArrayRelDataType, MapRelDataType, RowRelDataType, RowSchema}

import org.apache.calcite.jdbc.JavaTypeFactoryImpl
import org.apache.calcite.rel.`type`._
import org.apache.calcite.sql.`type`.SqlTypeName
import org.apache.calcite.sql.`type`.SqlTypeName._

import java.util

import scala.collection.JavaConverters._
import scala.collection.mutable

/**
  * Flink specific type factory that represents the interface between Flink's [[InternalType]]
  * and Calcite's [[RelDataType]].
  */
class FlinkTypeFactory(typeSystem: RelDataTypeSystem) extends JavaTypeFactoryImpl(typeSystem) {

  // NOTE: for future data types it might be necessary to
  // override more methods of RelDataTypeFactoryImpl

  private val seenTypes = mutable.HashMap[(InternalType, Boolean), RelDataType]()

  def createTypeFromInternalType(
      tp: InternalType,
      isNullable: Boolean): RelDataType = {

    val relType = seenTypes.get((tp, isNullable)) match {
      case Some(retType: RelDataType) => retType
      case None =>
        val refType = tp match {
          case InternalTypes.BOOLEAN => createSqlType(BOOLEAN)
          case InternalTypes.BYTE => createSqlType(TINYINT)
          case InternalTypes.SHORT => createSqlType(SMALLINT)
          case InternalTypes.INT => createSqlType(INTEGER)
          case InternalTypes.LONG => createSqlType(BIGINT)
          case InternalTypes.FLOAT => createSqlType(FLOAT)
          case InternalTypes.DOUBLE => createSqlType(DOUBLE)
          case InternalTypes.STRING => createSqlType(VARCHAR)

          // temporal types
          case InternalTypes.DATE => createSqlType(DATE)
          case InternalTypes.TIME => createSqlType(TIME)
          case InternalTypes.TIMESTAMP => createSqlType(TIMESTAMP)

          case InternalTypes.BINARY => createSqlType(VARBINARY)

          case InternalTypes.CHAR =>
            throw new TableException("Character type is not supported.")

          case rowType: RowType => new RowRelDataType(rowType, isNullable, this)

          case arrayType: ArrayType => new ArrayRelDataType(arrayType,
            createTypeFromInternalType(arrayType.getElementType, isNullable = true), isNullable)

          case mapType: MapType => new MapRelDataType(
            mapType,
            createTypeFromInternalType(mapType.getKeyType, isNullable = true),
            createTypeFromInternalType(mapType.getValueType, isNullable = true),
            isNullable)

          case _@t =>
            throw new TableException(s"Type is not supported: $t")
        }
        seenTypes.put((tp, isNullable), refType)
        refType
    }

    createTypeWithNullability(relType, isNullable)
  }

  /**
    * Creates a struct type with the input fieldNames and input fieldTypes using FlinkTypeFactory
    *
    * @param fieldNames field names
    * @param fieldTypes field types, every element is Flink's [[InternalType]]
    * @return a struct type with the input fieldNames, input fieldTypes, and system fields
    */
  def buildLogicalRowType(
      fieldNames: Seq[String],
      fieldTypes: Seq[InternalType]): RelDataType = {
    buildLogicalRowType(
      fieldNames,
      fieldTypes,
      fieldTypes.map(_ => true))
  }

  /**
    * Creates a struct type with the input fieldNames, input fieldTypes and input fieldNullables
    * using FlinkTypeFactory
    *
    * @param fieldNames     field names
    * @param fieldTypes     field types, every element is Flink's [[InternalType]]
    * @param fieldNullables field nullable properties
    * @return a struct type with the input fieldNames, input fieldTypes, and system fields
    */
  def buildLogicalRowType(
      fieldNames: Seq[String],
      fieldTypes: Seq[InternalType],
      fieldNullables: Seq[Boolean]): RelDataType = {
    val logicalRowTypeBuilder = builder
    val fields = fieldNames.zip(fieldTypes).zip(fieldNullables)
    fields foreach {
      case ((fieldName, fieldType), fieldNullable) =>
        logicalRowTypeBuilder.add(fieldName, createTypeFromInternalType(fieldType, fieldNullable))
    }
    logicalRowTypeBuilder.build
  }

  // ----------------------------------------------------------------------------------------------

  override def getJavaClass(`type`: RelDataType): java.lang.reflect.Type = {
    if (`type`.getSqlTypeName == FLOAT) {
      if (`type`.isNullable) {
        classOf[java.lang.Float]
      } else {
        java.lang.Float.TYPE
      }
    } else {
      super.getJavaClass(`type`)
    }
  }

  override def createSqlType(typeName: SqlTypeName, precision: Int): RelDataType = {
    // it might happen that inferred VARCHAR types overflow as we set them to Int.MaxValue
    // Calcite will limit the length of the VARCHAR type to 65536.
    if (typeName == VARCHAR && precision < 0) {
      createSqlType(typeName, getTypeSystem.getDefaultPrecision(typeName))
    } else {
      super.createSqlType(typeName, precision)
    }
  }

  override def createSqlType(typeName: SqlTypeName): RelDataType = {
    if (typeName == DECIMAL) {
      // if we got here, the precision and scale are not specified, here we
      // keep precision/scale in sync with our type system's default value,
      // see DecimalType.USER_DEFAULT.
      createSqlType(typeName, DecimalType.USER_DEFAULT.precision(),
        DecimalType.USER_DEFAULT.scale())
    } else {
      super.createSqlType(typeName)
    }
  }

  override def createTypeWithNullability(
      relDataType: RelDataType,
      isNullable: Boolean): RelDataType = {

    // nullability change not necessary
    if (relDataType.isNullable == isNullable) {
      return canonize(relDataType)
    }

    // change nullability
    val newType = super.createTypeWithNullability(relDataType, isNullable)

    canonize(newType)
  }

  override def leastRestrictive(types: util.List[RelDataType]): RelDataType = {
    val type0 = types.get(0)
    if (type0.getSqlTypeName != null) {
      val resultType = resolveAllIdenticalTypes(types)
      if (resultType.isDefined) {
        // result type for identical types
        return resultType.get
      }
    }
    // fall back to super
    super.leastRestrictive(types)
  }

  private def resolveAllIdenticalTypes(types: util.List[RelDataType]): Option[RelDataType] = {
    val allTypes = types.asScala

    val head = allTypes.head
    // check if all types are the same
    if (allTypes.forall(_ == head)) {
      // types are the same, check nullability
      val nullable = allTypes
        .exists(sqlType => sqlType.isNullable || sqlType.getSqlTypeName == SqlTypeName.NULL)
      // return type with nullability
      Some(createTypeWithNullability(head, nullable))
    } else {
      // types are not all the same
      if (allTypes.exists(_.getSqlTypeName == SqlTypeName.ANY)) {
        // one of the type was ANY.
        // we cannot generate a common type if it differs from other types.
        throw new TableException("Generic ANY types must have a common type information.")
      } else {
        // cannot resolve a common type for different input types
        None
      }
    }
  }

}

object FlinkTypeFactory {

  def toInternalType(relDataType: RelDataType): InternalType = relDataType.getSqlTypeName match {
    case BOOLEAN => InternalTypes.BOOLEAN
    case TINYINT => InternalTypes.BYTE
    case SMALLINT => InternalTypes.SHORT
    case INTEGER => InternalTypes.INT
    case BIGINT => InternalTypes.LONG
    case FLOAT => InternalTypes.FLOAT
    case DOUBLE => InternalTypes.DOUBLE
    case VARCHAR | CHAR => InternalTypes.STRING
    case DECIMAL => throw new RuntimeException("Not support yet.")

    // temporal types
    case DATE => InternalTypes.DATE
    case TIME => InternalTypes.TIME
    case TIMESTAMP => InternalTypes.TIMESTAMP

    case VARBINARY => InternalTypes.BINARY

    case NULL =>
      throw new TableException(
        "Type NULL is not supported. Null values must have a supported type.")

    // symbol for special flags e.g. TRIM's BOTH, LEADING, TRAILING
    // are represented as integer
    case SYMBOL => InternalTypes.INT

    case ROW if relDataType.isInstanceOf[RowRelDataType] =>
      val compositeRelDataType = relDataType.asInstanceOf[RowRelDataType]
      compositeRelDataType.rowType

    case ROW if relDataType.isInstanceOf[RelRecordType] =>
      val relRecordType = relDataType.asInstanceOf[RelRecordType]
      new RowSchema(relRecordType).internalType

    case ARRAY if relDataType.isInstanceOf[ArrayRelDataType] =>
      val arrayRelDataType = relDataType.asInstanceOf[ArrayRelDataType]
      arrayRelDataType.arrayType

    case MAP if relDataType.isInstanceOf[MapRelDataType] =>
      val mapRelDataType = relDataType.asInstanceOf[MapRelDataType]
      mapRelDataType.mapType

    case _@t =>
      throw new TableException(s"Type is not supported: $t")
  }
}
