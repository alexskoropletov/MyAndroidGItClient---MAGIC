package com.alexskoropletov.sync

interface Remote {
    fun fetchPointer(): IndexPointer
    fun tryPushIndex(expectedRevision: Long, blobId: String): Boolean
    fun putBlob(id: String, bytes: ByteArray)
    fun getBlob(id: String): ByteArray
}
