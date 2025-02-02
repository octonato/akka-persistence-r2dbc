/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.scaladsl

import scala.collection.immutable

import akka.NotUsed
import akka.persistence.query.DurableStateChange
import akka.persistence.query.Offset
import akka.persistence.state.scaladsl.DurableStateStore
import akka.stream.scaladsl.Source

// FIXME include this in Akka

/**
 * Query API for reading durable state objects.
 *
 * For Java API see [[akka.persistence.query.javadsl.DurableStateStoreBySliceQuery]].
 */
trait DurableStateStoreBySliceQuery[A] extends DurableStateStore[A] {

  /**
   * Get a source of the most recent changes made to objects with the given slice range since the passed in offset.
   *
   * A slice is deterministically defined based on the persistence id. The purpose is to evenly distribute all
   * persistence ids over the slices.
   *
   * Note that this only returns the most recent change to each object, if an object has been updated multiple times
   * since the offset, only the most recent of those changes will be part of the stream.
   *
   * This will return changes that occurred up to when the `Source` returned by this call is materialized. Changes to
   * objects made since materialization are not guaranteed to be included in the results.
   *
   * The [[DurableStateChange]] elements can be [[akka.persistence.query.UpdatedDurableState]] or `DeletedDurableState`.
   * `DeletedDurableState` is not implemented yet, see issue https://github.com/akka/akka/issues/30446.
   */
  def currentChangesBySlices(
      entityTypeHint: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[DurableStateChange[A], NotUsed]

  /**
   * Get a source of the most recent changes made to objects of the given slice range since the passed in offset.
   *
   * A slice is deterministically defined based on the persistence id. The purpose is to evenly distribute all
   * persistence ids over the slices.
   *
   * The returned source will never terminate, it effectively watches for changes to the objects and emits changes as
   * they happen.
   *
   * Not all changes that occur are guaranteed to be emitted, this call only guarantees that eventually, the most recent
   * change for each object since the offset will be emitted. In particular, multiple updates to a given object in quick
   * succession are likely to be skipped, with only the last update resulting in a change from this source.
   *
   * The [[DurableStateChange]] elements can be [[akka.persistence.query.UpdatedDurableState]] or `DeletedDurableState`.
   * `DeletedDurableState` is not implemented yet, see issue https://github.com/akka/akka/issues/30446.
   */
  def changesBySlices(
      entityTypeHint: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[DurableStateChange[A], NotUsed]

  def sliceForPersistenceId(persistenceId: String): Int

  def sliceRanges(numberOfRanges: Int): immutable.Seq[Range]

}
