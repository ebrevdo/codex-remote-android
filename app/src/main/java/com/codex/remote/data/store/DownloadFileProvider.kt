package com.codex.remote.data.store

import androidx.core.content.FileProvider
import com.codex.remote.R

/** Android instantiates this app-owned provider before the activity starts. */
class DownloadFileProvider : FileProvider(R.xml.download_file_paths)
