package com.raznoe.katana.model

import kotlinx.serialization.Serializable

/** A saved tone: parameter id -> value. This is the "librarian" unit. */
@Serializable
data class Patch(
    val name: String,
    val values: Map<String, Int>,
    val note: String = "",
)
