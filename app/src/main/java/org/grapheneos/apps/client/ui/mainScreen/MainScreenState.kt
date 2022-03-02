package org.grapheneos.apps.client.ui.mainScreen

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dagger.hilt.android.scopes.ActivityScoped
import org.grapheneos.apps.client.utils.AppSourceHelper

@ActivityScoped
class MainScreenState : ViewModel() {

    val grapheneOs = AppSourceHelper.gos
    val buildByGrapheneOs = AppSourceHelper.buildByGos
    val googleMirror = AppSourceHelper.google

    private val selectedFilter: MutableList<AppSourceHelper.BuildType> = mutableListOf()
    private val mutableFilterLiveData: MutableLiveData<List<AppSourceHelper.BuildType>> =
        MutableLiveData(selectedFilter)


    private fun removeFilter(item: AppSourceHelper.BuildType) {
        selectedFilter.remove(item)
        mutableFilterLiveData.postValue(selectedFilter)
    }

    private fun addFilter(filter: AppSourceHelper.BuildType) {
        if (!selectedFilter.contains(filter)) selectedFilter.add(filter)
        mutableFilterLiveData.postValue(selectedFilter)
    }

    fun modifyFilter(filter: AppSourceHelper.BuildType, isChecked: Boolean) {
        if (isChecked) addFilter(filter) else removeFilter(filter)
    }

    fun getFilter(): LiveData<List<AppSourceHelper.BuildType>> = mutableFilterLiveData

    fun getLastFilter() = selectedFilter

}