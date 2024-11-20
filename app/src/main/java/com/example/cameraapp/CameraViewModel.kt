package com.example.cameraapp

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

class CameraViewModel : ViewModel() {
//    Track camera permission status
    private val _hasCameraPermission = mutableStateOf(false)
    val hasCameraPermission: State<Boolean> = _hasCameraPermission

//    update permission status
    fun updateCameraPermission(granted:Boolean) {
        _hasCameraPermission.value = granted

    }

}