package com.rustedwax.hive

/**
 * Asks whether this device could scrobble, without writing anything.
 *
 * "It isn't scrobbling" has four separable causes and they need very different
 * fixes: no reachable node, no such account, a key that is not on the account's
 * posting authority, and no key loadable to sign with at all. Until now the only
 * way to tell them apart from the phone was to broadcast a real test entry —
 * onto a ledger that cannot be edited, for a listen that never happened. This
 * asks the same questions with two read-only calls and leaves no trace.
 *
 * ## Read-only by construction
 *
 * The two calls are injected rather than taken from a [HiveRpc] field, so this
 * class has no way to reach `broadcast` even by mistake, and every branch is
 * decidable in a unit test. The convenience constructor binds the two read
 * methods of a real client and nothing else.
 *
 * ## The private key is never handed to this
 *
 * The caller derives the `STM…` **public** posting key from the vault and passes
 * that. Nothing here can print, log or return a secret, because nothing here
 * ever receives one.
 */
class HiveConnectionCheck(
	private val headBlock: () -> HiveRpc.GlobalProperties,
	private val postingKeys: (String) -> List<String>,
) {
	constructor(rpc: HiveRpc = HiveRpc()) : this(
		headBlock = rpc::getDynamicGlobalProperties,
		postingKeys = rpc::getPostingPublicKeys,
	)

	/** One question and its answer, in the order a person would ask them. */
	data class Step(val name: String, val ok: Boolean, val detail: String)

	data class Report(val steps: List<Step>) {
		val ok: Boolean get() = steps.all { it.ok }

		val summary: String
			get() = steps.count { !it.ok }.let { failed ->
				if (failed == 0) {
					"All ${steps.size} checks passed."
				} else {
					"$failed of ${steps.size} checks failed."
				}
			}
	}

	fun run(username: String?, derivedPublicKey: String?): Report {
		val node = runCatching { headBlock() }

		// Deliberately attempted even when the head-block call failed. They are
		// different RPC methods over the same node list, and a single failure
		// reported as four is worse than useless — it hides which one is broken.
		val chainKeys = username?.let { name -> runCatching { postingKeys(name) } }

		val nodeStep = Step(
			name = "Hive node",
			ok = node.isSuccess,
			detail = node.fold(
				onSuccess = { "Reachable — head block ${it.headBlockNumber}" },
				onFailure = { "Unreachable — ${it.message ?: "no node answered"}" },
			),
		)

		val accountStep = when {
			username.isNullOrBlank() -> Step(
				name = "Account",
				ok = false,
				detail = "No Hive account is saved on this device.",
			)

			chainKeys == null || chainKeys.isFailure -> Step(
				name = "Account",
				ok = false,
				detail = "Lookup failed — " +
					(chainKeys?.exceptionOrNull()?.message ?: "no node answered"),
			)

			chainKeys.getOrThrow().isEmpty() -> Step(
				name = "Account",
				ok = false,
				detail = "No account named @$username, or it has no posting authority.",
			)

			else -> Step(
				name = "Account",
				ok = true,
				detail = "@$username found — ${chainKeys.getOrThrow().size} posting " +
					"key(s) on its authority.",
			)
		}

		val keyStep = when {
			derivedPublicKey.isNullOrBlank() -> Step(
				name = "Posting key",
				ok = false,
				detail = "No posting key is saved on this device, so nothing can be signed.",
			)

			!accountStep.ok -> Step(
				name = "Posting key",
				ok = false,
				detail = "Not checked — the account's authority could not be read.",
			)

			chainKeys?.getOrNull()?.none { it.trim() == derivedPublicKey } == true -> Step(
				name = "Posting key",
				ok = false,
				detail = "The saved key derives to $derivedPublicKey, which is not on " +
					"@$username's posting authority.",
			)

			else -> Step(
				name = "Posting key",
				ok = true,
				detail = "Matches @$username's posting authority.",
			)
		}

		val signingStep = Step(
			name = "Signing",
			ok = keyStep.ok,
			detail = if (keyStep.ok) {
				"Ready — scrobbles are signed on this device and only the signed " +
					"transaction is sent to a node."
			} else {
				"Not possible until the key above checks out."
			},
		)

		return Report(listOf(nodeStep, accountStep, keyStep, signingStep))
	}
}
