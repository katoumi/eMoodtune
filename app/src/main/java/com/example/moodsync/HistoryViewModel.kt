package com.example.moodsync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val historyDao = database.historyDao()

    private val searchQuery = MutableStateFlow("")

    val historyItems: LiveData<List<HistoryItem>> = searchQuery.flatMapLatest { query ->
        if (query.isBlank()) {
            historyDao.getAllHistory()
        } else {
            historyDao.searchHistory(query)
        }
    }.asLiveData()

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyDao.deleteAll()
        }
    }
}
