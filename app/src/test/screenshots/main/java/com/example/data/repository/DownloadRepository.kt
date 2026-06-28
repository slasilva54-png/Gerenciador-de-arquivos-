package com.example.data.repository

import com.example.data.database.DownloadTaskDao
import com.example.data.database.DownloadTaskEntity
import com.example.domain.model.DownloadTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DownloadRepository(private val dao: DownloadTaskDao) {
    val allTasks: Flow<List<DownloadTask>> = dao.getAllTasks().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun getTaskById(id: String): DownloadTask? {
        return dao.getTaskById(id)?.toDomain()
    }

    suspend fun insertOrUpdate(task: DownloadTask) {
        dao.insertOrUpdate(DownloadTaskEntity.fromDomain(task))
    }

    suspend fun delete(task: DownloadTask) {
        dao.delete(DownloadTaskEntity.fromDomain(task))
    }

    suspend fun deleteById(id: String) {
        dao.deleteById(id)
    }
}
