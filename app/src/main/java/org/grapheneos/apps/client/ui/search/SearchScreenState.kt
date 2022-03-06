package org.grapheneos.apps.client.ui.search

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.grapheneos.apps.client.App

class SearchScreenState(app: App) : AndroidViewModel(app) {

    private val mutableSearchQuery = MutableLiveData("")
    val searchQuery = mutableSearchQuery as LiveData<String>

    fun updateQuery(query: String) {
        println("updateQuery $query")
        mutableSearchQuery.postValue(query)
    }

    fun getCurrentQuery() = searchQuery.value ?: ""

}