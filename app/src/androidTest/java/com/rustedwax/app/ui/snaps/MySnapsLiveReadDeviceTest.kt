package com.rustedwax.app.ui.snaps

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rustedwax.app.storage.db.DatabaseQuarantine
import com.rustedwax.app.storage.db.LocalDatabase
import com.rustedwax.hive.HiveRpc
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 47E against the live chain, read-only, in a throwaway database: a Snap
 * rewritten in another frontend reaches its catalog row through a manual
 * refresh alone. Which Snap is supplied at run time, never committed:
 *
 *   -e liveOwner <account> -e livePermlink <permlink> -e liveBefore <base64 UTF-8 words>
 *
 * Without them the test is skipped. Needs network; makes no Hive write and
 * never touches the app's own data.
 */
@RunWith(AndroidJUnit4::class)
class MySnapsLiveReadDeviceTest {

	private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var name: String
	private lateinit var database: LocalDatabase

	@Before
	fun setUp() {
		name = "rustedwax-47e-live-${UUID.randomUUID()}.db"
		database = LocalDatabase(context, name)
	}

	@After
	fun tearDown() {
		runCatching { database.close() }
		val file = context.getDatabasePath(name)
		DatabaseQuarantine.existing(file).forEach { it.deleteRecursively() }
		(listOf("") + DatabaseQuarantine.COMPANION_SUFFIXES).forEach { File(file.path + it).delete() }
	}

	@Test
	fun externallyEditedSnapReconcilesOnManualRefresh() {
		val args = InstrumentationRegistry.getArguments()
		val owner = args.getString("liveOwner")
		val permlink = args.getString("livePermlink")
		val before = args.getString("liveBefore")?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }
		assumeTrue("no live Snap supplied", owner != null && permlink != null && before != null)

		val live = HiveRpc().getContent(owner!!, permlink!!)
		assertNotNull("live read", live)
		val expected = MySnapsIdentity.root(owner, live!!, rustedWaxApp = false)
		assertNotNull("the live object passes every check but the app tag", expected)
		assertNotEquals("the supplied earlier words must differ from the chain's", before, expected!!.userText)

		val rows = SqliteMySnapRows(database)
		rows.upsert(listOf(expected.copy(userText = before)), 1L)
		val c = MySnapsController(
			scope = CoroutineScope(Dispatchers.Unconfined), account = { owner }, catalog = { rows },
			confirmedRoots = { emptyList() }, io = Dispatchers.Unconfined,
			discovery = MySnapsDiscovery(listOf("n1"), { _, _, _ -> emptyList() }),
			readRoot = { a, p -> HiveRpc().getContent(a, p) },
		)
		c.open(emptyList())
		c.refresh(force = true, manual = true)

		val row = rows.page(owner, 10, null).single()
		assertEquals(expected.userText, row.userText)
		assertEquals(expected.videoId, row.videoId)
	}
}
