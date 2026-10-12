package com.photofind

import android.app.Application
import android.content.Context
import com.photofind.data.PhotoStore
import com.photofind.embed.Embedder
import com.photofind.search.SearchEngine

class PhotoFindApp : Application() {
    val store by lazy { PhotoStore(this) }
    val embedder by lazy { Embedder(this) }
    val search by lazy { SearchEngine(store, embedder) }
}

val Context.photoFind: PhotoFindApp
    get() = applicationContext as PhotoFindApp
