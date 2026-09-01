package com.rustedwax.android.sources

import com.rustedwax.core.ItemIdentity
import com.rustedwax.core.MetadataFields
import com.rustedwax.core.PlaybackSourceCapabilities
import com.rustedwax.core.SourceDescriptor
import com.rustedwax.core.TrackIdentity

/**
 * The source boundary shared by the platform gateway and the pure reducer.
 *
 * Adapters translate their own observation format into source-neutral metadata,
 * identity, and declared transport capabilities. They do not finalize, store,
 * enrich, or dispatch a listen.
 */
interface SourceAdapter {
	val descriptor: SourceDescriptor
	val playbackCapabilities: PlaybackSourceCapabilities
	fun trackIdentity(fields: MetadataFields?): TrackIdentity
	fun itemIdentity(fields: MetadataFields?): ItemIdentity
}
