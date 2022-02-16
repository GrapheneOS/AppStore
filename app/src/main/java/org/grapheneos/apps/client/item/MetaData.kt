package org.grapheneos.apps.client.item

data class MetaData(
    val timestamp: Long,
    val packages: Map<String, Package>
)