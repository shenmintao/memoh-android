package icu.minq.memoh.runtime
import icu.minq.memoh.data.*
import icu.minq.memoh.model.*
import icu.minq.memoh.network.RuntimeReducer
import org.junit.Assert.*
import org.junit.Test
class SteeringTest {
 @Test fun fullQueueReceiptsReconcileIndependentlyAndSurviveOldSnapshots() {
  val store=MemorySteeringStore()
  store.writeQueue("k",listOf(StoredSteering("a","run","first"),StoredSteering("b","run","second"),StoredSteering("c","run","third")))
  val run=RuntimeRun("run","t",status="running",steer_queue=listOf(SteerState("a","applied"),SteerState("b","pending"),SteerState("c","queued")))
  store.observe("k",run)
  assertEquals(listOf("applied","cached","queued"),store.readQueue("k").map { it.status })
  store.observe("k",run.copy(status="completed",steer_queue=listOf(SteerState("a","queued"),SteerState("b","applied"),SteerState("c","rejected"))))
  assertEquals(listOf("applied","applied","rejected"),store.readQueue("k").map { it.status })
  store.observe("k",null)
  assertEquals(listOf("a","b","c"),store.readQueue("k").map { it.id })
  assertEquals(listOf("applied","applied","rejected"),store.readQueue("k").map { it.status })
 }
 @Test fun queueDeltaPreservesTranscriptAndUnrelatedPatchesPreserveQueue() {
  val queue=listOf(SteerState("a","queued"),SteerState("b","pending"))
  val run=RuntimeRun("run","t",status="running",messages=listOf(MessageBlock(1,"text",content="working")))
  val state=RuntimeState("s","e",1,run,false,true,true)
  val next=RuntimeReducer.delta(state,"s","e",2,RuntimeDelta(run=RunPatch("run",steer_queue=queue)))
  assertEquals(queue,next.run!!.steer_queue)
  assertEquals(run.messages,next.run!!.messages)
  assertTrue(next.steerQueueSupported)
  val status=RuntimeReducer.delta(next,"s","e",3,RuntimeDelta(run=RunPatch("run",status="completed")))
  assertEquals(queue,status.run!!.steer_queue)
 }
 @Test fun sharedStorePublishesReceiptAndDoesNotDowngradeSettledState() {
  val store=MemorySteeringStore()
  val key=steeringKey("account","b","s")
  store.write(key,StoredSteering("id","run","text","queued"))
  val revision=store.changes.value
  store.observe(key,RuntimeRun("run","t",status="completed",steer=SteerState("id","applied")))
  assertTrue(store.changes.value>revision)
  assertEquals("applied",store.read(key)?.status)
  store.observe(key,RuntimeRun("run","t",status="running",steer=SteerState("id","queued")))
  store.observe(key,RuntimeRun("run","t",status="errored",steer=SteerState("id","rejected")))
  store.observe(key,null)
  assertEquals("applied",store.read(key)?.status)
  assertNull(store.read(steeringKey("other","b","s")))
 }

 @Test fun terminalOrReplacedRunDoesNotLeaveSupplementQueued() {
  val queued = StoredSteering("id", "run", "text", "queued")
  assertEquals("unknown", queued.observe(RuntimeRun("run", "t", status="completed")).status)
  assertEquals("unknown", queued.observe(RuntimeRun("other", "t", status="running")).status)
  assertEquals("unknown", queued.observe(null).status)
  assertEquals("applied", queued.observe(RuntimeRun("run", "t", status="completed", steer=SteerState("id", "applied"))).status)
  val applied = queued.copy(status="applied")
  assertEquals(applied.copy(turnId="t"), applied.observe(RuntimeRun("run", "t", status="running", steer=SteerState("id", "queued"))))
 }

 @Test fun receiptRequiresMatchingRunAndId() {
  val pending = StoredSteering("id", "run", "text", "unknown")
  assertEquals("applied", pending.observe(RuntimeRun("run", "t", status="running", steer=SteerState("id", "applied"))).status)
  assertEquals(pending, pending.observe(RuntimeRun("other", "t", status="running", steer=SteerState("id", "applied"))))
  assertEquals(pending.copy(turnId="t"), pending.observe(RuntimeRun("run", "t", status="completed")))
  assertEquals("unknown", pending.observe(RuntimeRun("run", "t", status="running", steer=SteerState("id", "rejected", error="steer_status_unknown"))).status)
 }
 @Test fun steerDeltaKeepsTranscriptAndRejectsStaleOrdering() {
  val run = RuntimeRun("run", "t", status="running", messages=listOf(MessageBlock(1,"text",content="answer")))
  val state = RuntimeState("s", "epoch", 1, run, false, true)
  val delta = RuntimeDelta(run=RunPatch("run", steer=SteerState("id", "applied", "new input")))
  val next = RuntimeReducer.delta(state, "s", "epoch", 2, delta)
  assertEquals("applied", next.run!!.steer!!.status)
  assertEquals(run.messages, next.run!!.messages)
  assertTrue(next.steerSupported)
  assertTrue(RuntimeReducer.delta(next, "s", "epoch", 2, delta).needsSnapshot)
 }
}
