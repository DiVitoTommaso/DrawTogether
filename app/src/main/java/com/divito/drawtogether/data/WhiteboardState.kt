package com.divito.drawtogether.data

import android.graphics.Color
import android.graphics.Paint
import com.divito.drawtogether.DrawMode
import com.divito.drawtogether.extensions.toPixels

data class WhiteboardState(
    var drawColor: Int = Color.BLACK, // to save
    var drawStroke: Float = 0.0.toFloat(), // to save
    var eraseStroke: Float = 0.0.toFloat(), // to save
    var drawMode: DrawMode = DrawMode.DRAW, // to save
    var isSearching: Boolean = false, // not to save
    var isConnected: Boolean = false, // not to save
    var isWhiteboardLoaded: Boolean = false, // not to save
    var isBatteryLow: Boolean = false // not to save
)