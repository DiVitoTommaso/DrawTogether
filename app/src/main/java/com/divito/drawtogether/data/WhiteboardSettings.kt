package com.divito.drawtogether.data

import android.net.Uri

data class WhiteboardSettings(
    var orientation: String = "", // to save
    var projectName: String = "", // to save
    var pictureUri: Uri? = null, // to save
    var isClient: Boolean = false, // to save
    var batterySave: Boolean = false, // not to save
    var askJoin: Boolean = false, // not to save
    var exitSave: Boolean = false, // not to save
    var loop: Boolean = false // not to save
)