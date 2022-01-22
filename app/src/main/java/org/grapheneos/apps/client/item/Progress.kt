package org.grapheneos.apps.client.item

data class Progress(
    val read: Long,
    val total: Long,
    val doneInPercent: Double,
    val taskCompleted: Boolean
)