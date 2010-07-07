/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.broker.store.hawtdb

import java.{lang=>jl}
import java.{util=>ju}

import model.{AddQueue, AddQueueEntry, AddMessage}
import org.apache.activemq.apollo.dto.HawtDBStoreDTO
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.fusesource.hawtbuf.proto.MessageBuffer
import org.fusesource.hawtbuf.proto.PBMessage
import org.apache.activemq.util.LockFile
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import org.fusesource.hawtdb.internal.journal.{JournalListener, Journal, Location}
import org.fusesource.hawtdispatch.TaskTracker

import org.fusesource.hawtbuf.AsciiBuffer._
import org.apache.activemq.broker.store.hawtdb.model.Type._
import org.apache.activemq.broker.store.hawtdb.model._
import org.fusesource.hawtbuf._
import org.fusesource.hawtdispatch.ScalaDispatch._
import collection.mutable.{LinkedHashMap, HashMap, ListBuffer}
import collection.JavaConversions
import ju.{TreeSet, HashSet}

import org.fusesource.hawtdb.api._
import org.apache.activemq.apollo.broker.{DispatchLogging, Log, Logging, BaseService}
import org.apache.activemq.apollo.util.TimeCounter
import org.apache.activemq.apollo.store._

object HawtDBClient extends Log {
  val BEGIN = -1
  val COMMIT = -2
  val ROLLBACK = -3

  val DATABASE_LOCKED_WAIT_DELAY = 10 * 1000

  val CLOSED_STATE = 1
  val OPEN_STATE = 2
}

/**
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class HawtDBClient(hawtDBStore: HawtDBStore) extends DispatchLogging {
  import HawtDBClient._
  import Helpers._

  override def log: Log = HawtDBClient

  def dispatchQueue = hawtDBStore.dispatchQueue


  private val pageFileFactory = new TxPageFileFactory()
  private var journal: Journal = null

  private var lockFile: LockFile = null
  private val trackingGen = new AtomicLong(0)
  private val lockedDatatFiles = new HashSet[jl.Integer]()

  private var recovering = false
  private var nextRecoveryPosition: Location = null
  private var lastRecoveryPosition: Location = null
  private var recoveryCounter = 0

  @volatile
  var rootBuffer = (new DatabaseRootRecord.Bean()).freeze

  @volatile
  var storedRootBuffer = (new DatabaseRootRecord.Bean()).freeze


  val next_batch_counter = new AtomicInteger(0)
  private var batches = new LinkedHashMap[Int, (Location, ListBuffer[Update])]()

  /////////////////////////////////////////////////////////////////////
  //
  // Helpers
  //
  /////////////////////////////////////////////////////////////////////

  private def directory = config.directory

  private def journalMaxFileLength = config.journalLogSize

  private def checkpointInterval = config.indexFlushInterval

  private def cleanupInterval = config.cleanupInterval

  private def failIfDatabaseIsLocked = config.failIfLocked

  private def pageFile = pageFileFactory.getTxPageFile()


  /////////////////////////////////////////////////////////////////////
  //
  // Public interface used by the HawtDBStore
  //
  /////////////////////////////////////////////////////////////////////

  var config: HawtDBStoreDTO = null

  def lock(func: => Unit) {
    val lockFileName = new File(directory, "lock")
    lockFile = new LockFile(lockFileName, true)
    if (failIfDatabaseIsLocked) {
      lockFile.lock()
      func
    } else {
      val locked = try {
        lockFile.lock()
        true
      } catch {
        case e: IOException =>
          false
      }
      if (locked) {
        func
      } else {
        info("Database " + lockFileName + " is locked... waiting " + (DATABASE_LOCKED_WAIT_DELAY / 1000) + " seconds for the database to be unlocked.")
        dispatchQueue.dispatchAfter(DATABASE_LOCKED_WAIT_DELAY, TimeUnit.MILLISECONDS, ^ {
          hawtDBStore.executor_pool {
            lock(func _)
          }
        })
      }
    }
  }

  val schedual_version = new AtomicInteger()

  def start(onComplete:Runnable) = {
    lock {

      journal = new Journal()
      journal.setDirectory(directory)
      journal.setMaxFileLength(config.journalLogSize)
      journal.setMaxWriteBatchSize(config.journalBatchSize);
      journal.setChecksum(true);
      journal.setListener( new JournalListener{
        def synced(writes: Array[JournalListener.Write]) = {
          var onCompletes = List[Runnable]()
          withTx { tx=>
            val helper = new TxHelper(tx)
            writes.foreach { write=>
              val func = write.getAttachment.asInstanceOf[(TxHelper, Location)=>List[Runnable]]
              onCompletes = onCompletes ::: func(helper, write.getLocation)
            }
            helper.storeRootBean
          }
          onCompletes.foreach( _.run )
        }
      })

      if( config.archiveDirectory!=null ) {
        journal.setDirectoryArchive(config.archiveDirectory)
        journal.setArchiveDataLogs(true)
      }
      journal.start

      pageFileFactory.setFile(new File(directory, "db"))
      pageFileFactory.setDrainOnClose(false)
      pageFileFactory.setSync(true)
      pageFileFactory.setUseWorkerThread(true)
      pageFileFactory.setPageSize(config.indexPageSize)
      pageFileFactory.setCacheSize(config.indexCacheSize);

      pageFileFactory.open()

      val initialized = withTx { tx =>
          if (!tx.allocator().isAllocated(0)) {
            val helper = new TxHelper(tx)
            import helper._

            val rootPage = tx.alloc()
            assert(rootPage == 0)

            rootBean.setQueueIndexPage(alloc(QUEUE_INDEX_FACTORY))
            rootBean.setMessageKeyIndexPage(alloc(MESSAGE_KEY_INDEX_FACTORY))
            rootBean.setDataFileRefIndexPage(alloc(DATA_FILE_REF_INDEX_FACTORY))
            rootBean.setMessageRefsIndexPage(alloc(MESSAGE_REFS_INDEX_FACTORY))
            rootBean.setSubscriptionIndexPage(alloc(SUBSCRIPTIONS_INDEX_FACTORY))
            storedRootBuffer = rootBean.freeze
            helper.storeRootBean

            true
          } else {
            rootBuffer = tx.get(DATABASE_ROOT_RECORD_ACCESSOR, 0)
            storedRootBuffer = rootBuffer;
            false
          }
      }

      if( initialized ) {
        pageFile.flush()
      }

      recover(onComplete)

      // Schedual periodic jobs.. they keep executing while schedual_version remains the same.
      schedualCleanup(schedual_version.get())
      // schedualFlush(schedual_version.get())
    }
  }

  def stop() = {
    schedual_version.incrementAndGet
    journal.close
    pageFileFactory.close
    lockFile.unlock
  }

  def addQueue(record: QueueRecord, callback:Runnable) = {
    val update = new AddQueue.Bean()
    update.setKey(record.key)
    update.setName(record.name)
    update.setQueueType(record.queueType)
    _store(update, callback)
  }

  def removeQueue(queueKey: Long, callback:Runnable) = {
    val update = new RemoveQueue.Bean()
    update.setKey(queueKey)
    _store(update, callback)
  }

  def store(txs: Seq[HawtDBStore#HawtDBUOW], callback:Runnable) {
    var batch = ListBuffer[TypeCreatable]()
    txs.foreach {
      tx =>
        tx.actions.foreach {
          case (msg, action) =>
            if (action.messageRecord != null) {
              val update: AddMessage.Bean = action.messageRecord
              batch += update
            }
            action.enqueues.foreach {
              queueEntry =>
                val update: AddQueueEntry.Bean = queueEntry
                batch += update
            }
            action.dequeues.foreach {
              queueEntry =>
                val queueKey = queueEntry.queueKey
                val queueSeq = queueEntry.queueSeq
                batch += new RemoveQueueEntry.Bean().setQueueKey(queueKey).setQueueSeq(queueSeq)
            }
        }
    }
    _store(batch, callback)
  }


  def purge(callback: Runnable) = {
    _store(new Purge.Bean(), callback)
  }

  def listQueues: Seq[Long] = {
    val rc = ListBuffer[Long]()
    withTx { tx =>
      val helper = new TxHelper(tx)
      import JavaConversions._
      import helper._

      queueIndex.iterator.foreach { entry =>
        rc += entry.getKey.longValue
      }
    }
    rc
  }

  def getQueueStatus(queueKey: Long): Option[QueueStatus] = {
    withTx { tx =>
        val helper = new TxHelper(tx)
        import JavaConversions._
        import helper._

        val queueRecord = queueIndex.get(queueKey)
        if (queueRecord != null) {
          val rc = new QueueStatus
          rc.record = new QueueRecord
          rc.record.key = queueKey
          rc.record.name = queueRecord.getInfo.getName
          rc.record.queueType = queueRecord.getInfo.getQueueType
          rc.count = queueRecord.getCount.toInt
          rc.size = queueRecord.getSize

          // TODO
          // rc.first =
          // rc.last =

          Some(rc)
        } else {
          None
        }
    }
  }

  def listQueueEntryGroups(queueKey: Long, limit: Int) : Seq[QueueEntryRange] = {
    withTx { tx =>
        val helper = new TxHelper(tx)
        import JavaConversions._
        import helper._
        import Predicates._

        val queueRecord = queueIndex.get(queueKey)
        if (queueRecord != null) {
          val entryIndex = queueEntryIndex(queueRecord)

          var rc = ListBuffer[QueueEntryRange]()
          var group:QueueEntryRange = null

          entryIndex.iterator.foreach { entry =>
            if( group == null ) {
              group = new QueueEntryRange
              group.firstQueueSeq = entry.getKey.longValue
            }
            group.lastQueueSeq = entry.getKey.longValue
            group.count += 1
            group.size += entry.getValue.getSize
            if( group.count == limit) {
              rc += group
              group = null
            }
          }

          if( group!=null ) {
            rc += group
          }
          rc
        } else {
          null
        }
    }
  }

  def getQueueEntries(queueKey: Long, firstSeq:Long, lastSeq:Long): Seq[QueueEntryRecord] = {
    var rc = ListBuffer[QueueEntryRecord]()
    withTx { tx =>
      val helper = new TxHelper(tx)
      import JavaConversions._
      import helper._
      import Predicates._

      val queueRecord = queueIndex.get(queueKey)
      if (queueRecord != null) {
        val entryIndex = queueEntryIndex(queueRecord)

        val where = and(gte(new jl.Long(firstSeq)), lte(new jl.Long(lastSeq)))
        entryIndex.iterator( where ).foreach {
          entry =>
            val record: QueueEntryRecord = entry.getValue
            rc += record
        }
      } else {
        rc = null
      }
    }
    rc
  }

  val metric_load_from_index = new TimeCounter
  val metric_load_from_journal = new TimeCounter

  def loadMessages(requests: ListBuffer[(Long, (Option[MessageRecord])=>Unit)]) = {
    val locations = withTx { tx =>
      val helper = new TxHelper(tx)
      import JavaConversions._
      import helper._
      requests.flatMap { case (messageKey, callback)=>
        val location = metric_load_from_index.time {
          messageKeyIndex.get(messageKey)
        }
        if( location==null ) {
          debug("Message not indexed.  Journal location could not be determined for message: %s", messageKey)
          callback(None)
          None
        } else {
          Some((location, callback))
        }
      }
    }

    locations.foreach { case (location, callback)=>
      val addMessage = metric_load_from_journal.time {
        load(location, classOf[AddMessage.Getter])
      }
      callback( addMessage.map( x => toMessageRecord(x) ) )
    }

  }

  def loadMessage(messageKey: Long): Option[MessageRecord] = {
    metric_load_from_index.start { end =>
      withTx { tx =>
        val helper = new TxHelper(tx)
        import JavaConversions._
        import helper._

        val location = messageKeyIndex.get(messageKey)
        end()

        if (location != null) {
          metric_load_from_journal.time {
            load(location, classOf[AddMessage.Getter]) match {
              case Some(x) =>
                val messageRecord: MessageRecord = x
                Some(messageRecord)
              case None => None
            }
          }
        } else {
          debug("Message not indexed.  Journal location could not be determined for message: %s", messageKey)
          None
        }
      }
    }
  }


  /////////////////////////////////////////////////////////////////////
  //
  // Batch/Transactional interface to storing/accessing journaled updates.
  //
  /////////////////////////////////////////////////////////////////////

  private def load[T <: TypeCreatable](location: Location, expected: Class[T]): Option[T] = {
    try {
      load(location) match {
          case (updateType, batch, data) =>
            val decoded = expected.cast(decode(location, updateType, data))
            val rc = Some(decoded)
            rc
      }
    } catch {
      case e: Throwable =>
        debug(e, "Could not load journal record at: %s", location)
        None
    }
  }

  private def _store(updates: Seq[TypeCreatable], onComplete: Runnable): Unit = {
    val batch = next_batch_id
    begin(batch)
    updates.foreach {
      update =>
        _store(batch, update, null)
    }
    commit(batch, onComplete)
  }

  private def _store(update: TypeCreatable, onComplete: Runnable): Unit = _store(-1, update, onComplete)

  val metric_journal_append = new TimeCounter
  val metric_index_update = new TimeCounter

  /**
   * All updated are are funneled through this method. The updates are logged to
   * the journal and then the indexes are update.  onFlush will be called back once
   * this all completes and the index has the update.
   *
   * @throws IOException
   */
  private def _store(batch: Int, update: TypeCreatable, onComplete: Runnable): Unit = {
    val kind = update.asInstanceOf[TypeCreatable]
    val frozen = update.freeze
    val baos = new DataByteArrayOutputStream(frozen.serializedSizeFramed + 5)
    baos.writeByte(kind.toType().getNumber())
    baos.writeInt(batch)
    frozen.writeFramed(baos)

    val buffer = baos.toBuffer()
    append(buffer) { (helper, location) =>
      metric_index_update.time {
        executeStore(helper, location, batch, update, onComplete)
      }
    }
  }

  /**
   */
  private def begin(batch: Int): Unit = {
    val baos = new DataByteArrayOutputStream(5)
    baos.writeByte(BEGIN)
    baos.writeInt(batch)
    append(baos.toBuffer) { (helper,location) =>
      executeBegin(helper, location, batch)
    }
  }

  /**
   */
  private def commit(batch: Int, onComplete: Runnable): Unit = {
    val baos = new DataByteArrayOutputStream(5)
    baos.writeByte(COMMIT)
    baos.writeInt(batch)
    append(baos.toBuffer) { (helper,location) =>
      executeCommit(helper, location, batch, onComplete)
    }
  }

  private def rollback(batch: Int, onComplete: Runnable): Unit = {
    val baos = new DataByteArrayOutputStream(5)
    baos.writeByte(ROLLBACK)
    baos.writeInt(batch)
    append(baos.toBuffer) { (helper,location) =>
      executeRollback(helper, location, batch, onComplete)
    }
  }

  def load(location: Location) = {
    var data = read(location)
    val editor = data.bigEndianEditor
    val updateType = editor.readByte()
    val batch = editor.readInt
    (updateType, batch, data)
  }

  /////////////////////////////////////////////////////////////////////
  //
  // Methods related to recovery
  //
  /////////////////////////////////////////////////////////////////////

  /**
   * Recovers the journal and rollsback any in progress batches that
   * were in progress and never committed.
   *
   * @throws IOException
   * @throws IOException
   * @throws IllegalStateException
   */
  def recover(onComplete:Runnable): Unit = {
    recoveryCounter = 0
    lastRecoveryPosition = null
    val start = System.currentTimeMillis()
    incrementalRecover


    _store(new AddTrace.Bean().setMessage("RECOVERED"), ^ {
      // Rollback any batches that did not complete.
      batches.keysIterator.foreach {
        batch =>
          rollback(batch, null)
      }

      val end = System.currentTimeMillis()
      info("Processed %d operations from the journal in %,.3f seconds.", recoveryCounter, ((end - start) / 1000.0f))
      onComplete.run
    })
  }


  /**
   * incrementally recovers the journal.  It can be run again and again
   * if the journal is being appended to.
   */
  def incrementalRecover(): Unit = {

    // Is this our first incremental recovery pass?
    if (lastRecoveryPosition == null) {
      if (rootBuffer.hasFirstBatchLocation) {
        // we have to start at the first in progress batch usually...
        nextRecoveryPosition = rootBuffer.getFirstBatchLocation
      } else {
        // but perhaps there were no batches in progress..
        if (rootBuffer.hasLastUpdateLocation) {
          // then we can just continue from the last update applied to the index
          lastRecoveryPosition = rootBuffer.getLastUpdateLocation
          nextRecoveryPosition = journal.getNextLocation(lastRecoveryPosition)
        } else {
          // no updates in the index?.. start from the first record in the journal.
          nextRecoveryPosition = journal.getNextLocation(null)
        }
      }
    } else {
      nextRecoveryPosition = journal.getNextLocation(lastRecoveryPosition)
    }

    try {
      recovering = true

      // Continue recovering until journal runs out of records.
      while (nextRecoveryPosition != null) {
        lastRecoveryPosition = nextRecoveryPosition
        recover(lastRecoveryPosition)
        nextRecoveryPosition = journal.getNextLocation(lastRecoveryPosition)
      }

    } finally {
      recovering = false
    }
  }

  /**
   * Recovers the logged record at the specified location.
   */
  def recover(location: Location): Unit = {
    var data = journal.read(location)

    val editor = data.bigEndianEditor
    val updateType = editor.readByte()
    val batch = editor.readInt()

    withTx { tx=>
      val helper = new TxHelper(tx)
      updateType match {
        case BEGIN => executeBegin(helper, location, batch)
        case COMMIT => executeCommit(helper, location, batch, null)
        case _ =>
          val update = decode(location, updateType, data)
          executeStore(helper, location, batch, update, null)
      }
      helper.storeRootBean
    }

    recoveryCounter += 1
  }


  /////////////////////////////////////////////////////////////////////
  //
  // Methods for Journal access
  //
  /////////////////////////////////////////////////////////////////////

  private def append(data: Buffer)(cb: (TxHelper, Location) => List[Runnable]): Unit = {
    metric_journal_append.start { end =>
      def cbintercept(tx:TxHelper,location:Location) = {
        end()
        cb(tx, location)
      }
      journal.write(data, cbintercept _ )
    }
  }

  def read(location: Location) = journal.read(location)

  /////////////////////////////////////////////////////////////////////
  //
  // Methods that execute updates stored in the journal by indexing them
  // Used both in normal operation and durring recovery.
  //
  /////////////////////////////////////////////////////////////////////

  private def executeBegin(helper:TxHelper, location: Location, batch: Int):List[Runnable] = {
    assert(batches.get(batch).isEmpty)
    batches.put(batch, (location, ListBuffer()))
    Nil
  }

  private def executeCommit(helper:TxHelper, location: Location, batch: Int, onComplete: Runnable):List[Runnable] = {
    // apply all the updates in the batch as a single unit of work.
    batches.remove(batch) match {
      case Some((_, updates)) =>
        // When recovering.. we only want to redo updates that committed
        // after the last update location.
        if (!recovering || isAfterLastUpdateLocation(location)) {
            // index the updates
            updates.foreach {
              update =>
                index(helper, update.update, update.location)
            }
            helper.updateLocations(location)
        }
      case None =>
        // when recovering..  we are more lax due recovery starting
        // in the middle of a stream of in progress batches
        assert(recovering)
    }
    if(onComplete!=null) {
      return List(onComplete)
    } else {
      Nil
    }
  }

  private def executeRollback(helper:TxHelper, location: Location, batch: Int, onComplete: Runnable): List[Runnable] = {
    // apply all the updates in the batch as a single unit of work.
    batches.remove(batch) match {
      case Some((_, _)) =>
        if (!recovering || isAfterLastUpdateLocation(location)) {
          helper.updateLocations(location)
        }
      case None =>
        // when recovering..  we are more lax due recovery starting
        // in the middle of a stream of in progress batches
        assert(recovering)
    }
    if(onComplete!=null) {
      return List(onComplete)
    } else {
      Nil
    }
  }

  private def executeStore(helper:TxHelper, location: Location, batch: Int, update: TypeCreatable, onComplete: Runnable): List[Runnable] = {
    if (batch == -1) {
      // update is not part of the batch..

      // When recovering.. we only want to redo updates that happen
      // after the last update location.
      if (!recovering || isAfterLastUpdateLocation(location)) {
          index(helper, update, location)
          helper.updateLocations(location)
      }

      if ( onComplete != null) {
        return List(onComplete)
      }
    } else {

      // only the commit/rollback in batch can have an onCompelte handler
      assert(onComplete == null)

      // if the update was part of a batch don't apply till the batch is committed.
      batches.get(batch) match {
        case Some((_, updates)) =>
          updates += Update(update, location)
        case None =>
          // when recovering..  we are more lax due recovery starting
          // in the middle of a stream of in progress batches
          assert(recovering)
      }
    }
    return Nil
  }


  private def index(helper:TxHelper, update: TypeCreatable, location: Location): Unit = {
    import JavaConversions._
    import helper._

    def removeMessage(key:Long) = {
      val location = messageKeyIndex.remove(key)
      if (location != null) {
        val fileId:jl.Integer = location.getDataFileId()
        addAndGet(dataFileRefIndex, fileId, -1)
      } else {
        if( !recovering ) {
          error("Cannot remove message, it did not exist: %d", key)
        }
      }
    }

    def removeQueue(queueKey:Long) = {
      val queueRecord = queueIndex.remove(queueKey)
      if (queueRecord != null) {
        val trackingIndex = queueTrackingIndex(queueRecord)
        val entryIndex = queueEntryIndex(queueRecord)

        trackingIndex.iterator.foreach { entry=>
          val messageKey = entry.getKey
          if( addAndGet(messageRefsIndex, messageKey, -1) == 0 ) {
            // message is no longer referenced.. we can remove it..
            removeMessage(messageKey.longValue)
          }
        }

        entryIndex.destroy
        trackingIndex.destroy
      }

    }

    update match {
      case x: AddMessage.Getter =>

        val messageKey = x.getMessageKey()
        if (messageKey > rootBean.getLastMessageKey) {
          rootBean.setLastMessageKey(messageKey)
        }

        val prevLocation = messageKeyIndex.put(messageKey, location)
        if (prevLocation != null) {
          // Message existed.. undo the index update we just did. Chances
          // are it's a transaction replay.
          messageKeyIndex.put(messageKey, prevLocation)
          if (location == prevLocation) {
            warn("Message replay detected for: %d", messageKey)
          } else {
            error("Message replay with different location for: %d", messageKey)
          }
        } else {
          val fileId:jl.Integer = location.getDataFileId()
          addAndGet(dataFileRefIndex, fileId, 1)
        }

      case x: AddQueueEntry.Getter =>

        val queueKey = x.getQueueKey
        val queueRecord = queueIndex.get(queueKey)
        if (queueRecord != null) {
          val trackingIndex = queueTrackingIndex(queueRecord)
          val entryIndex = queueEntryIndex(queueRecord)

          // a message can only appear once in a queue (for now).. perhaps we should
          // relax this constraint.
          val messageKey = x.getMessageKey
          val queueSeq = x.getQueueSeq

          val existing = trackingIndex.put(messageKey, queueSeq)
          if (existing == null) {
            val previous = entryIndex.put(queueSeq, x.freeze)
            if (previous == null) {

              val queueRecordUpdate = queueRecord.copy
              queueRecordUpdate.setCount(queueRecord.getCount + 1)
              queueRecordUpdate.setSize(queueRecord.getSize + x.getSize)
              queueIndex.put(queueKey, queueRecordUpdate.freeze)

              addAndGet(messageRefsIndex, new jl.Long(messageKey), 1)
            } else {
              // TODO perhaps treat this like an update?
              error("Duplicate queue entry seq %d", x.getQueueSeq)
            }
          } else {
            error("Duplicate queue entry message %d was %d", x.getMessageKey, existing)
          }
        } else {
          error("Queue not found: %d", x.getQueueKey)
        }

      case x: RemoveQueueEntry.Getter =>
        val queueKey = x.getQueueKey
        val queueRecord = queueIndex.get(queueKey)
        if (queueRecord != null) {
          val trackingIndex = queueTrackingIndex(queueRecord)
          val entryIndex = queueEntryIndex(queueRecord)

          val queueSeq = x.getQueueSeq
          val queueEntry = entryIndex.remove(queueSeq)
          if (queueEntry != null) {
            val messageKey = queueEntry.getMessageKey
            val existing = trackingIndex.remove(messageKey)
            if (existing != null) {
              if( addAndGet(messageRefsIndex, new jl.Long(messageKey), -1) == 0 ) {
                // message is no longer referenced.. we can remove it..
                removeMessage(messageKey)
              }
            } else {
              if( !recovering ) {
                error("Tracking entry not found for message %d", queueEntry.getMessageKey)
              }
            }
          } else {
            if( !recovering ) {
              error("Queue entry not found for seq %d", x.getQueueSeq)
            }
          }
        } else {
          if( !recovering ) {
            error("Queue not found: %d", x.getQueueKey)
          }
        }

      case x: AddQueue.Getter =>
        val queueKey = x.getKey
        if (queueIndex.get(queueKey) == null) {

          if (queueKey > rootBean.getLastQueueKey) {
            rootBean.setLastQueueKey(queueKey)
          }

          val queueRecord = new QueueRootRecord.Bean
          queueRecord.setEntryIndexPage(alloc(QUEUE_ENTRY_INDEX_FACTORY))
          queueRecord.setTrackingIndexPage(alloc(QUEUE_TRACKING_INDEX_FACTORY))
          queueRecord.setInfo(x)
          queueIndex.put(queueKey, queueRecord.freeze)
        }

      case x: RemoveQueue.Getter =>
        removeQueue(x.getKey)

      case x: AddTrace.Getter =>
        // trace messages are informational messages in the journal used to log
        // historical info about store state.  They don't update the indexes.

      case x: Purge.Getter =>
        // Remove all the queues...
        val queueKeys = queueIndex.iterator.map( _.getKey )
        queueKeys.foreach { key =>
          removeQueue(key.longValue)
        }

        // Remove stored messages...
        messageKeyIndex.clear
        messageRefsIndex.clear
        dataFileRefIndex.clear
        rootBean.setLastMessageKey(0)

        cleanup(_tx);
        info("Store purged.");

      case x: AddSubscription.Getter =>
      case x: RemoveSubscription.Getter =>

      case x: AddMap.Getter =>
      case x: RemoveMap.Getter =>
      case x: PutMapEntry.Getter =>
      case x: RemoveMapEntry.Getter =>

      case x: OpenStream.Getter =>
      case x: WriteStream.Getter =>
      case x: CloseStream.Getter =>
      case x: RemoveStream.Getter =>
    }
  }


  /////////////////////////////////////////////////////////////////////
  //
  // Periodic Maintance
  //
  /////////////////////////////////////////////////////////////////////

  def schedualFlush(version:Int): Unit = {
    def try_flush() = {
      if (version == schedual_version.get) {
        hawtDBStore.executor_pool {
          flush
          schedualFlush(version)
        }
      }
    }
    dispatchQueue.dispatchAfter(config.indexFlushInterval, TimeUnit.MILLISECONDS, ^ {try_flush})
  }

  def flush() = {
    val start = System.currentTimeMillis()
    pageFile.flush
    val end = System.currentTimeMillis()
    if (end - start > 1000) {
      warn("Index flush latency: %,.3f seconds", ((end - start) / 1000.0f))
    }
  }

  def schedualCleanup(version:Int): Unit = {
    def try_cleanup() = {
      if (version == schedual_version.get) {
        hawtDBStore.executor_pool {
          withTx {tx =>
            cleanup(tx)
          }
          schedualCleanup(version)
        }
      }
    }
    dispatchQueue.dispatchAfter(config.cleanupInterval, TimeUnit.MILLISECONDS, ^ {try_cleanup})
  }

  /**
   * @param tx
   * @throws IOException
   */
  def cleanup(tx:Transaction) = {
    val helper = new TxHelper(tx)
    import JavaConversions._
    import helper._

    debug("Cleanup started.")
    val gcCandidateSet = new TreeSet[jl.Integer](journal.getFileMap().keySet())

    // Don't cleanup locked data files
    if (lockedDatatFiles != null) {
      gcCandidateSet.removeAll(lockedDatatFiles)
    }

    // Don't GC files that we will need for recovery..

    // Notice we are using the storedRootBuffer and not the rootBuffer field.
    // rootBuffer has the latest updates, which they may not survive restart.
    val upto = if (storedRootBuffer.hasFirstBatchLocation) {
      Some(storedRootBuffer.getFirstBatchLocation.getDataFileId)
    } else {
      if (storedRootBuffer.hasLastUpdateLocation) {
        Some(storedRootBuffer.getLastUpdateLocation.getDataFileId)
      } else {
        None
      }
    }

    upto match {
      case Some(dataFile) =>
        var done = false
        while (!done && !gcCandidateSet.isEmpty()) {
          val last = gcCandidateSet.last()
          if (last.intValue >= dataFile) {
            gcCandidateSet.remove(last)
          } else {
            done = true
          }
        }

      case None =>
    }

    if (!gcCandidateSet.isEmpty() ) {
      dataFileRefIndex.iterator.foreach { entry =>
        gcCandidateSet.remove(entry.getKey)
      }
      if (!gcCandidateSet.isEmpty()) {
        debug("Cleanup removing the data files: %s", gcCandidateSet)
        journal.removeDataFiles(gcCandidateSet)
      }
    }
    debug("Cleanup done.")
  }

  /////////////////////////////////////////////////////////////////////
  //
  // Helper Methods / Classes
  //
  /////////////////////////////////////////////////////////////////////

  private case class Update(update: TypeCreatable, location: Location)

  private class TxHelper(val _tx: Transaction) {
    lazy val queueIndex = QUEUE_INDEX_FACTORY.open(_tx, rootBuffer.getQueueIndexPage)
    lazy val dataFileRefIndex = DATA_FILE_REF_INDEX_FACTORY.open(_tx, rootBuffer.getDataFileRefIndexPage)
    lazy val messageKeyIndex = MESSAGE_KEY_INDEX_FACTORY.open(_tx, rootBuffer.getMessageKeyIndexPage)
    lazy val messageRefsIndex = MESSAGE_REFS_INDEX_FACTORY.open(_tx, rootBuffer.getMessageRefsIndexPage)
    lazy val subscriptionIndex = SUBSCRIPTIONS_INDEX_FACTORY.open(_tx, rootBuffer.getSubscriptionIndexPage)

    def addAndGet[K](index:SortedIndex[K, jl.Integer], key:K, amount:Int):Int = {
      var counter = index.get(key)
      if( counter == null ) {
        if( amount!=0 ) {
          index.put(key, amount)
        }
        amount
      } else {
        val update = counter.intValue + amount
        if( update == 0 ) {
          index.remove(key)
        } else {
          index.put(key, update)
        }
        update
      }
    }

    def queueEntryIndex(root: QueueRootRecord.Getter) = QUEUE_ENTRY_INDEX_FACTORY.open(_tx, root.getEntryIndexPage)

    def queueTrackingIndex(root: QueueRootRecord.Getter) = QUEUE_TRACKING_INDEX_FACTORY.open(_tx, root.getTrackingIndexPage)

    def alloc(factory: IndexFactory[_, _]) = {
      val rc = _tx.alloc
      factory.create(_tx, rc)
      rc
    }

    val rootBean = rootBuffer.copy

    def lastUpdateLocation(location:Location) = {
      rootBean.setLastUpdateLocation(location)
    }

    def updateLocations(lastUpdate: Location): Unit = {
      rootBean.setLastUpdateLocation(lastUpdate)
      if (batches.isEmpty) {
        rootBean.clearFirstBatchLocation
      } else {
        rootBean.setFirstBatchLocation(batches.head._2._1)
      }
    }

    def storeRootBean() = {
      val frozen = rootBean.freeze
      rootBuffer = frozen
      _tx.put(DATABASE_ROOT_RECORD_ACCESSOR, 0, rootBuffer)

      // Since the index flushes updates async, hook a callback to know when
      // the update has hit disk.  storedRootBuffer is used by the
      // cleanup task to know when which data logs are safe to cleanup.
      _tx.onFlush(^{
        storedRootBuffer = frozen
      })

    }

  }

  private def withTx[T](func: (Transaction) => T): T = {
    val tx = pageFile.tx
    var ok = false
    try {
      val rc = func(tx)
      ok = true
      rc
    } finally {
      if (ok) {
        tx.commit
      } else {
        tx.rollback
      }
      tx.close
    }
  }

  // Gets the next batch id.. after a while we may wrap around
  // start producing batch ids from zero
  val next_batch_id = {
    var rc = next_batch_counter.getAndIncrement
    while (rc < 0) {
      // We just wrapped around.. reset the counter to 0
      // Use a CAS operation so that only 1 thread resets the counter
      next_batch_counter.compareAndSet(rc + 1, 0)
      rc = next_batch_counter.getAndIncrement
    }
    rc
  }

  private def isAfterLastUpdateLocation(location: Location) = {
    val lastUpdate: Location = rootBuffer.getLastUpdateLocation
    lastUpdate.compareTo(location) < 0
  }

}