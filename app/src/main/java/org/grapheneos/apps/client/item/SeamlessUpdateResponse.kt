package org.grapheneos.apps.client.item

data class SeamlessUpdateResponse(
    val updatedSuccessfully : List<String> = emptyList(),
    val failedToUpdate : List<String> = emptyList(),
    val requireConformation : List<String> = emptyList(),
    val executedSuccessfully : Boolean = false
)