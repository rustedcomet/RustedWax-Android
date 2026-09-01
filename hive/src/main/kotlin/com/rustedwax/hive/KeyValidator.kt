package com.rustedwax.hive

/**
 * Checks a typed-in posting key against the account's on-chain authority.
 *
 * This is the local-signing equivalent of the extension's Keychain login: the
 * extension proved account ownership by having Keychain sign a challenge, then
 * verified the recovered public key against `posting.key_auths`
 * (`posting-key-verify.ts`). Holding the key outright, we skip the signature
 * round-trip and compare the derived public key directly — same guarantee,
 * fewer moving parts.
 *
 * Fails closed, exactly as upstream does: any parse or network problem is a
 * rejection, never an "assume it's fine".
 */
class KeyValidator(private val rpc: HiveRpc = HiveRpc()) {

	sealed interface Result {
		data class Valid(val username: String, val publicKey: String) : Result
		data class Invalid(val reason: String) : Result
	}

	fun validate(username: String, wif: String): Result {
		val account = username.trim().lowercase().removePrefix("@")
		if (account.isEmpty()) {
			return Result.Invalid("Enter your Hive username.")
		}

		val key = HiveKey.fromWif(wif)
			?: return Result.Invalid(
				"That doesn't look like a posting key. It should start with 5 and be " +
					"51 characters. (An owner or active key would also be refused here — " +
					"this app only ever wants the posting key.)",
			)

		val chainKeys = try {
			rpc.getPostingPublicKeys(account)
		} catch (e: Exception) {
			return Result.Invalid("Couldn't reach a Hive node to check the key: ${e.message}")
		}

		if (chainKeys.isEmpty()) {
			return Result.Invalid("No account named @$account, or it has no posting authority.")
		}

		val derived = key.publicKeyString
		if (chainKeys.none { it.trim() == derived }) {
			return Result.Invalid(
				"That key isn't on @$account's posting authority. It derives to " +
					"$derived, but the account lists ${chainKeys.joinToString(", ")}.",
			)
		}

		return Result.Valid(account, derived)
	}
}
