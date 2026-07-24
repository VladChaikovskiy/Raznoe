package com.raznoe.katana.model

import kotlinx.serialization.Serializable

/** A user-added backing/jam track (a persisted content URI + display name). */
@Serializable
data class Track(val uri: String, val name: String)
