/***********************************************************************
 * Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.process.tube

import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom._
import com.vividsolutions.jts.geom.impl.CoordinateArraySequence
import org.geotools.data.simple.SimpleFeatureCollection
import org.geotools.referencing.GeodeticCalculator
import org.joda.time.format.DateTimeFormat
import org.locationtech.geomesa.features.ScalaSimpleFeatureFactory
import org.locationtech.geomesa.utils.collection.SelfClosingIterator
import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType._
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.text.WKTUtils
import org.opengis.feature.simple.SimpleFeature

object TubeBuilder {
  val DefaultDtgField = "dtg"
}

/**
 * Build a tube for input to a TubeSelect by buffering and binning the input
 * tubeFeatures into SimpleFeatures that can be used as inputs to Geomesa queries
 */
abstract class TubeBuilder(val tubeFeatures: SimpleFeatureCollection,
                           val bufferDistance: Double,
                           val maxBins: Int) extends LazyLogging {

  val calc = new GeodeticCalculator()
  val dtgField = tubeFeatures.getSchema.getDtgField.getOrElse(TubeBuilder.DefaultDtgField)
  val geoFac = new GeometryFactory

  val GEOM_PROP = "geom"

  val tubeType = SimpleFeatureTypes.createType("tubeType", s"$GEOM_PROP:Geometry:srid=4326,start:Date,end:Date")
  val builder = ScalaSimpleFeatureFactory.featureBuilder(tubeType)

  // default to ISO 8601 date format
  val df = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

  def getGeom(sf: SimpleFeature): Geometry = sf.getAttribute(0).asInstanceOf[Geometry]
  def getStartTime(sf: SimpleFeature): Date = sf.getAttribute(1).asInstanceOf[Date]
  def getEndTime(sf: SimpleFeature): Date = sf.getAttribute(2).asInstanceOf[Date]

  def bufferGeom(geom: Geometry, meters: Double): Geometry = {
    import org.locationtech.geomesa.utils.geotools.Conversions.RichGeometry
    geom.buffer(metersToDegrees(meters, geom.safeCentroid()))
  }

  def metersToDegrees(meters: Double, point: Point): Double = {
    logger.debug("Buffering: "+meters.toString + " "+WKTUtils.write(point))

    calc.setStartingGeographicPoint(point.getX, point.getY)
    calc.setDirection(0, meters)
    val dest2D = calc.getDestinationGeographicPoint
    val destPoint = geoFac.createPoint(new Coordinate(dest2D.getX, dest2D.getY))
    point.distance(destPoint)
  }

  def buffer(simpleFeatures: Iterator[SimpleFeature], meters:Double): Iterator[SimpleFeature] =
    simpleFeatures.map { sf =>
      val bufferedGeom = bufferGeom(getGeom(sf), meters)
      builder.reset()
      builder.init(sf)
      builder.set(GEOM_PROP, bufferedGeom)
      builder.buildFeature(sf.getID)
    }

  // transform the input tubeFeatures into the intermediate SF used by the
  // tubing code consisting of three attributes (geom, startTime, endTime)
  //
  // handle date parsing from input -> TODO revisit date parsing...
  def transform(tubeFeatures: SimpleFeatureCollection, dtgField: String): Iterator[SimpleFeature] = {
    SelfClosingIterator(tubeFeatures.features).map { sf =>
      val date = sf.getAttribute(dtgField) match {
        case s: String => df.parseDateTime(s).toDate
        case d => d
      }

      if (date == null) {
        logger.error("Unable to retrieve date field from input tubeFeatures...ensure there a field named " + dtgField)
        throw new IllegalArgumentException("Unable to retrieve date field from input tubeFeatures...ensure there a field named \"" + dtgField + "\"")
      }

      builder.reset()
      builder.buildFeature(sf.getID, Array(sf.getDefaultGeometry, date, null))
    }
  }

  def createTube: Iterator[SimpleFeature]
}

/**
 * Build a tube with no gap filling - only buffering and binning
 */
class NoGapFill(tubeFeatures: SimpleFeatureCollection,
                bufferDistance: Double,
                maxBins: Int) extends TubeBuilder(tubeFeatures, bufferDistance, maxBins) with LazyLogging {

  // Bin ordered features into maxBins that retain order by date then union by geometry
  def timeBinAndUnion(features: Iterable[SimpleFeature], maxBins: Int): Iterator[SimpleFeature] = {
    val numFeatures = features.size
    if (numFeatures == 0) { Iterator.empty } else {
      val binSize = if (maxBins > 0) {
        numFeatures / maxBins + (if (numFeatures % maxBins == 0 ) 0 else 1)
      } else {
        numFeatures
      }
      features.grouped(binSize).zipWithIndex.map { case(bin, idx) => unionFeatures(bin.toSeq, idx.toString) }
    }
  }

  // Union features to create a single geometry and single combined time range
  def unionFeatures(orderedFeatures: Seq[SimpleFeature], id: String): SimpleFeature = {
    import scala.collection.JavaConversions._
    val geoms = orderedFeatures.map { sf => getGeom(sf) }
    val unionGeom = geoFac.buildGeometry(geoms).union
    val min = getStartTime(orderedFeatures.head)
    val max = getStartTime(orderedFeatures.last)

    builder.reset()
    builder.buildFeature(id, Array(unionGeom, min, max))
  }

  override def createTube: Iterator[SimpleFeature] = {
    logger.debug("Creating tube with no gap filling")

    val transformed = transform(tubeFeatures, dtgField)
    val buffered = buffer(transformed, bufferDistance)
    val sortedTube = buffered.toSeq.sortBy { sf => getStartTime(sf).getTime }

    logger.debug(s"sorted tube size: ${sortedTube.size}")
    timeBinAndUnion(sortedTube, maxBins)
  }
}

/**
 * Build a tube with gap filling that draws a line between time-ordered features
 * from the given tubeFeatures
 */
class LineGapFill(tubeFeatures: SimpleFeatureCollection,
                  bufferDistance: Double,
                  maxBins: Int) extends TubeBuilder(tubeFeatures, bufferDistance, maxBins) with LazyLogging {

  val id = new AtomicInteger(0)

  def nextId: String = id.getAndIncrement.toString

  override def createTube: Iterator[SimpleFeature] = {
    import org.locationtech.geomesa.utils.geotools.Conversions.RichGeometry

    logger.debug("Creating tube with line gap fill")

    val transformed = transform(tubeFeatures, dtgField)
    val sortedTube = transformed.toSeq.sortBy { sf => getStartTime(sf).getTime }
    val pointsAndTimes = sortedTube.map(sf => (getGeom(sf).safeCentroid(), getStartTime(sf)))

    val lineFeatures = if (pointsAndTimes.length == 1) {
      val (p1, t1) = pointsAndTimes.head
      logger.debug("Only a single result - can't create a line")
      Iterator(builder.buildFeature(nextId, Array(p1, t1, t1)))
    } else {
      pointsAndTimes.sliding(2).map { case Seq((p1, t1), (p2, t2)) =>
        val geo = if (p1.equals(p2)) { p1 } else {
          new LineString(new CoordinateArraySequence(Array(p1.getCoordinate, p2.getCoordinate)), geoFac)
        }
        logger.debug(s"Created line-filled geom: ${WKTUtils.write(geo)} from ${WKTUtils.write(p1)} and ${WKTUtils.write(p2)}")
        builder.buildFeature(nextId, Array(geo, t1, t2))
      }
    }

    buffer(lineFeatures, bufferDistance)
  }
}