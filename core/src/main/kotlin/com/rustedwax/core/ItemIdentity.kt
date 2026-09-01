package com.rustedwax.core

/**
 * What a finalized listen was, in terms no source owns.
 *
 * ## Why this exists
 *
 * Shared finalized identity uses a source-scoped item ID and typed canonical
 * link contract. Each adapter publishes its own opaque identifier and, when
 * available, a shareable link; shared code never requires a source-specific
 * identifier shape.
 *
 * ## Deliberately three questions, not a hierarchy
 *
 * The three are separable and each is separately load-bearing:
 *
 *  - [isSourceProven] without [sourceItemId] is an ordinary page-backed outcome —
 *    the site is proven, the item is not yet known — and it is the state the
 *    whole resolver chain exists to repair. Collapsing it into "unidentified"
 *    would delete the distinction finalization depends on.
 *  - [canonicalLink] is separate from [sourceItemId] because a source profile
 *    decides whether a link is mandatory. Some sources have a stable id and no
 *    public URL at all.
 *
 * ## What this does not do
 *
 * It does not describe *how* the verdict was reached. Route names, corroboration
 * and refusal text stay with the source's own identity type, where they can be
 * as specific as that source needs. This is the part shared code is allowed to
 * read.
 */
interface ItemIdentity {

	/** Human-readable provenance for logs and diagnostics. Never parsed. */
	val source: String

	/**
	 * The stable identifier this source publishes for the item, if it is known.
	 *
	 * Opaque to everything above the adapter: shared code stores, compares and
	 * forwards it, and never parses or validates its shape.
	 */
	val sourceItemId: String?

	/** A shareable link to the item, when the source publishes one. */
	val canonicalLink: String?

	/**
	 * The source itself is proven, whether or not the item is identified.
	 *
	 * False means the evidence could not establish even that much, which is the
	 * one state that is never scrobbled.
	 */
	val isSourceProven: Boolean
}
