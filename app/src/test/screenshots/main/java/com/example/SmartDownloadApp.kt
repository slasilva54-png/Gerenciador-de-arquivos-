package com.example

import android.app.Application
import com.example.data.database.AppDatabase
import com.example.data.repository.DownloadRepository
import com.example.engine.DownloadManager

class SmartDownloadApp : Application() {
    lateinit var repository: DownloadRepository
    lateinit var downloadManager: DownloadManager

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.getDatabase(this)
        repository = DownloadRepository(database.downloadTaskDao())
        downloadManager = DownloadManager(this, repository)
        instance = this
    }

    companion object {
        lateinit var instance: SmartDownloadApp
            private set
    }
}
