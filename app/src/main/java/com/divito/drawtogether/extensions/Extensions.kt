package com.divito.drawtogether.extensions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.ramotion.circlemenu.CircleMenuView
import org.json.JSONObject
import java.io.ByteArrayOutputStream

fun Float.toPixels(context: Context): Float {
    return this * (context.resources
        .displayMetrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT)
}

fun Float.toDP(context: Context): Float {
    return this / (context.resources
        .displayMetrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT)
}

fun CircleMenuView.getMenuButton(): FloatingActionButton? {
    return try {
        // click listeners works only using reflection on menu button
        Log.i("WhiteboardActivity", "Java reflection: access to hidden whiteboard menu button")
        val field = this::class.java.getDeclaredField("mMenuButton")
        field.isAccessible = true
        field.get(this) as FloatingActionButton
    } catch (e: Exception) {
        null
    }
}

fun JSONObject.clear() {
    val it = keys()
    while (it.hasNext()) {
        it.next()
        it.remove()
    }

}

fun String.toBitmap(): Bitmap? {
    val decodedString: ByteArray = Base64.decode(this, Base64.DEFAULT)
    return BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
}

fun Bitmap.toBase64String(): String? {
    val byteArrayBitmapStream = ByteArrayOutputStream()
    compress(
        Bitmap.CompressFormat.PNG, 100,
        byteArrayBitmapStream
    )
    val b: ByteArray = byteArrayBitmapStream.toByteArray()
    return Base64.encodeToString(b, Base64.DEFAULT)
}