/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.ground.persistence.remote.firebase

import com.google.android.ground.model.AuditInfo
import com.google.android.ground.model.geometry.Coordinates
import com.google.android.ground.model.geometry.Geometry
import com.google.android.ground.model.geometry.LineString
import com.google.android.ground.model.geometry.LinearRing
import com.google.android.ground.model.geometry.MultiPolygon
import com.google.android.ground.model.geometry.Point
import com.google.android.ground.model.geometry.Polygon
import com.google.android.ground.proto.AuditInfo as AuditInfoProto
import com.google.android.ground.proto.Coordinates as CoordinatesProto
import com.google.android.ground.proto.Geometry as GeometryProto
import com.google.android.ground.proto.LinearRing as LinearRingProto
import com.google.android.ground.proto.MultiPolygon as MultiPolygonProto
import com.google.android.ground.proto.Point as PointProto
import com.google.android.ground.proto.Polygon as PolygonProto
import com.google.protobuf.Timestamp
import java.util.Date

private fun AuditInfo.toProtoBuf(): AuditInfoProto {
  val builder =
    AuditInfoProto.newBuilder()
      .setUserId(user.id)
      .setPhotoUrl(user.photoUrl)
      .setDisplayName(user.displayName)
      .setClientTimestamp(clientTimestamp.toTimestamp())
  if (serverTimestamp != null) {
    builder.setServerTimestamp(serverTimestamp.toTimestamp())
  }
  return builder.build()
}

private fun Date.toTimestamp(): Timestamp = Timestamp.newBuilder().setSeconds(time * 1000).build()

private fun Geometry.toProtoBuf(): GeometryProto {
  val geometryBuilder = GeometryProto.newBuilder()
  when (this) {
    is Point -> geometryBuilder.setPoint(toProtoBuf())
    is MultiPolygon -> geometryBuilder.setMultiPolygon(toProtoBuf())
    is Polygon -> geometryBuilder.setPolygon(toProtoBuf())
    is LineString,
    is LinearRing -> throw UnsupportedOperationException("Unsupported type $this")
  }
  return geometryBuilder.build()
}

private fun Coordinates.toProtoBuf(): CoordinatesProto =
  CoordinatesProto.newBuilder().setLatitude(lat).setLongitude(lng).build()

private fun Point.toProtoBuf(): PointProto =
  PointProto.newBuilder().setCoordinates(coordinates.toProtoBuf()).build()

private fun LinearRing.toProtoBuf(): LinearRingProto =
  LinearRingProto.newBuilder().addAllCoordinates(coordinates.map { it.toProtoBuf() }).build()

private fun Polygon.toProtoBuf(): PolygonProto =
  PolygonProto.newBuilder()
    .setShell(shell.toProtoBuf())
    .addAllHoles(holes.map { it.toProtoBuf() })
    .build()

private fun MultiPolygon.toProtoBuf(): MultiPolygonProto =
  MultiPolygonProto.newBuilder().addAllPolygons(polygons.map { it.toProtoBuf() }).build()
