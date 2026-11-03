package com.alexskoropletov.sync

object Limits {
    const val MAX_FILE = 64 * 1024 * 1024
    const val MAX_BLOB = MAX_FILE + 64 * 1024
    const val MASS_DELETE_LIMIT = 30
}
