package com.rustedwax.app.replay.reference.current

import com.rustedwax.core.MetadataFields
import com.rustedwax.app.replay.reference.phase01.MediaMetadata

/**
 * The one member the mirror needs that the stand-in media surface cannot supply
 * by itself.
 *
 * Production's `MediaMetadata.asFields()` wraps an `android.media.MediaMetadata`
 * in [com.rustedwax.app.detect.AndroidMetadataFields]. The stand-in in
 * `reference/phase01/Android.kt` already *is* a [MetadataFields] — that is how
 * the old reference reaches the real, unmodified `MetadataDump` and
 * `YouTubeProbe` bodies — so the conversion is the identity.
 *
 * Written here rather than in the generated file so the mirror stays
 * byte-identical to what ships, and written as `= this` rather than as a second
 * reader so a parity difference can never originate in a copy of the adapter.
 */
internal fun MediaMetadata.asFields(): MetadataFields = this
