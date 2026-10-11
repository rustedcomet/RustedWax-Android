package com.rustedwax.app.snaps

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rustedwax.hive.HivePreparationResult
import com.rustedwax.hive.HiveRpc
import com.rustedwax.hive.PreparedHiveTransaction
import com.rustedwax.hive.SnapContainerResolver
import com.rustedwax.hive.TxSerializer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Isolated synthetic B1 reproduction only; invoked in separate instrumentation runs.
 * The host kills the test package between phases and corrupts ONLY its private XML.
 * No keys, real Hive transmissions, Activity or production-account data are involved.
 */
@RunWith(AndroidJUnit4::class)
class SnapAttemptMalformedXmlDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val phase = InstrumentationRegistry.getArguments().getString("rwB1Phase")
    private val manifest = File(context.filesDir, "rw-b1-manifest.json")
    private val backing = File(context.applicationInfo.dataDir,
        "shared_prefs/${SharedPreferencesSnapAttemptStore.FILE}.xml")

    private fun log(message: String) = Log.i("RustedWaxB1Probe", message)

    @Test
    fun prepare() {
        assumeTrue(phase == "prepare")
        assertEquals("isolated package only", "com.rustedwax.app.freshtest", context.packageName)
        val rows = SharedPreferencesSnapAttemptStore(context).load()
        assertNotNull("new store is readable", rows)
        assertTrue("isolated test store must start empty", rows!!.isEmpty())
        val suffix = UUID.randomUUID().toString().replace("-", "").take(6)
        val account = "rwb1$suffix"
        val target = SnapReplyTarget.of(account, "rustedwax-reply-1-$suffix")!!
        val holder = SnapWriteGuards.process(context)
        val fake = FakePort(account, target)
        val outcome = SnapEditor(fake, guards = holder)
            .edit(account, target, SnapEditKind.REPLY, "original words changed")
        assertTrue(outcome is SnapEditor.Outcome.Uncertain)
        assertEquals("new-store control signs once", 1, fake.signatures)
        assertEquals("new-store control sends once", 1, fake.sends)
        val original = holder.current(account, target) as SnapWriteGuards.EditAttempt
        val txId = original.prepared.txId
        assertTrue(backing.exists())
        assertEquals(1, SharedPreferencesSnapAttemptStore(context).load()!!.size)
        JSONObject()
            .put("account", account)
            .put("permlink", target.permlink)
            .put("txId", txId)
            .put("pid", Process.myPid())
            .put("signed", original.prepared.signedTransactionJson)
            .put("row", SharedPreferencesSnapAttemptStore(context).load()!!.values.single())
            .put("prepareSignatures", fake.signatures)
            .put("prepareSends", fake.sends)
            .toString().let { manifest.writeText(it) }
        log("PREPARED pid=${Process.myPid()} originalTx=$txId fileExists=${backing.exists()}")
        Thread.sleep(120_000)
        throw AssertionError("host should kill isolated prepare process")
    }

    /** A fake port that can only create dummy transactions in memory. */
    private class FakePort(private val account: String, private val target: SnapReplyTarget) : SnapHivePort {
        val txId = "synthetic-b1-${UUID.randomUUID()}"
        var signatures = 0
        var sends = 0
        override fun resolveContainer(): SnapContainerResolver.Result =
            error("synthetic-only: publication not supported")
        override fun prepareComment(operation: TxSerializer.CommentOp, author: String): HivePreparationResult {
            assertEquals(account, author)
            signatures++
            return HivePreparationResult.Ready(
                PreparedHiveTransaction(JSONObject().put("synthetic", txId).toString(), txId, 2_000_000_060L))
        }
        override fun broadcastPrepared(prepared: PreparedHiveTransaction, author: String): HiveRpc.BroadcastResult {
            sends++
            return HiveRpc.BroadcastResult.AcceptedUnconfirmed(prepared.txId, "fake", "synthetic")
        }
        override fun observeTransaction(txId: String, expirationEpochSec: Long) =
            HiveRpc.TransactionEvidence.UNAVAILABLE
        override fun contentExists(author: String, permlink: String): Boolean? = true
        override fun readComment(author: String, permlink: String): SnapChainComment {
            assertEquals(target.author, author)
            assertEquals(target.permlink, permlink)
            return SnapChainComment(author, permlink, account,
                "rustedwax-snap-parent", "", "original words", "{}")
        }
    }

    @Test
    fun verify() {
        assumeTrue(phase == "verify" || phase == "valid")
        assertEquals("isolated package only", "com.rustedwax.app.freshtest", context.packageName)
        val data = JSONObject(manifest.readText())
        val account = data.getString("account")
        val target = SnapReplyTarget.of(account, data.getString("permlink"))!!
        assertNotEquals("requires genuinely new Linux process", data.getInt("pid"), Process.myPid())
        assertTrue("backing file must exist", backing.exists())
        val xmlBefore = backing.readText()
        assertTrue("no backup can conceal the injected condition", !File(backing.path + ".bak").exists())
        if (phase == "verify") {
            assertEquals("host-injected malformed bytes", "<map><rw-b1-broken", xmlBefore)
        }
        val rows = SharedPreferencesSnapAttemptStore(context).load()
        val holder = SnapWriteGuards.process(context)
        val previous = holder.current(account, target)
        val fake = FakePort(account, target)
        val outcome = SnapEditor(fake, guards = holder)
            .edit(account, target, SnapEditKind.REPLY, "replacement words")
        val result = JSONObject()
            .put("phase", phase).put("pid", Process.myPid()).put("oldPid", data.getInt("pid"))
            .put("account", account).put("contentId", target.contentId).put("originalTx", data.getString("txId"))
            .put("loadWasNull", rows == null).put("loadedRows", rows?.let { JSONObject(it) } ?: JSONObject.NULL)
            .put("slot", previous?.javaClass?.simpleName ?: "FREE")
            .put("fakeSignatures", fake.signatures).put("fakeSends", fake.sends)
            .put("attemptedTx", if (fake.signatures > 0) fake.txId else JSONObject.NULL)
            .put("outcome", outcome.javaClass.simpleName).put("backingBefore", xmlBefore)
            .put("verdict", if (phase == "valid") "CONTROL" else if (previous == null && rows != null && rows.isEmpty() && fake.signatures == 1) "REPRODUCED" else "NOT_REPRODUCED")
        File(context.filesDir, "rw-b1-$phase-result.json").writeText(result.toString())
        log("RESULT $result")
        if (phase == "valid") {
            assertNotNull("valid store restores original", previous)
            assertEquals(data.getString("txId"), (previous as SnapWriteGuards.EditAttempt).prepared.txId)
            assertEquals(data.getString("signed"), previous.prepared.signedTransactionJson)
            assertEquals(data.getString("row"), rows!!.values.single())
            assertEquals(0, fake.signatures)
            assertEquals(0, fake.sends)
            assertEquals("valid control must retain its exact XML", xmlBefore, backing.readText())
        } else {
            assertTrue("bounded result: either blocked or one dummy signature", fake.signatures in 0..1)
            assertEquals("every fake signature has one fake transmission", fake.signatures, fake.sends)
        }
    }
}
