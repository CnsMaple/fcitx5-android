package org.fcitx.fcitx5.android.data.clipboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent helper launched from the IME toolbar to pick a file/image and hand
 * it to the live [ClipSyncClient] for upload. The IME service cannot receive an
 * activity result, so this short-lived activity bridges the picker.
 */
class ClipPickerActivity : ComponentActivity() {

    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) ClipSyncClient.current?.uploadPicked(uri, type)
        finish()
    }

    private var type = "File"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = intent.getStringExtra(EXTRA_TYPE) ?: "File"
        pick.launch(if (type == "Image") "image/*" else "*/*")
    }

    companion object {
        const val EXTRA_TYPE = "type"
    }
}
