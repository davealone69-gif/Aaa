package com.example.avatar.sdk.api

import android.content.Context
import android.net.Uri
import com.example.avatar.sdk.storage.AvatarLibrary

class AvatarEngine private constructor(context: Context) {
    private val library = AvatarLibrary(context.applicationContext)
    fun importGlb(uri: Uri, name: String = "Imported avatar") = library.importGlb(uri, name)
    fun load(id: String) = library.load(id)
    fun listAvatars() = library.list()
    fun delete(id: String) = library.delete(id)
    fun exportGlb(id: String, destination: Uri) = library.export(id, destination)
    companion object { @Volatile private var instance: AvatarEngine? = null; fun initialize(context: Context): AvatarEngine = instance ?: synchronized(this) { instance ?: AvatarEngine(context).also { instance = it } } }
}
