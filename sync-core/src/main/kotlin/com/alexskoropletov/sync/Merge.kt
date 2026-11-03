package com.alexskoropletov.sync

data class MergePlan(
    val entries: Map<String, FileEntry>,
    val conflicts: Int,
)

fun merge(
    base: Map<String, FileEntry>,
    local: Map<String, FileEntry>,
    remote: Map<String, FileEntry>,
    localChanges: Set<String>,
    device: String,
): MergePlan {
    val paths = (base.keys + local.keys + remote.keys).toSortedSet()
    val taken = paths.toMutableSet()
    val result = linkedMapOf<String, FileEntry>()
    var conflicts = 0

    for (path in paths) {
        val was = base[path]
        val left = local[path]
        val right = remote[path]
        val localChanged = path in localChanges
        val remoteChanged = when {
            right == null -> false
            was == null -> true
            else -> was.hash != right.hash || was.deleted != right.deleted
        }

        when {
            !localChanged && !remoteChanged -> (left ?: was)?.let { result[path] = it }
            !localChanged && remoteChanged -> right?.let { result[path] = it }
            localChanged && !remoteChanged -> left?.let { result[path] = it }
            else -> {
                if (left == null || right == null) {
                    (left ?: right)?.let { result[path] = it }
                    continue
                }
                if (left.hash == right.hash && left.deleted == right.deleted) {
                    val blob = left.blobId.ifEmpty { right.blobId }
                    val version = maxOf(left.version, right.version)
                    val by = if (left.version >= right.version) left.updatedBy else right.updatedBy
                    result[path] = left.copy(version = version, blobId = blob, updatedBy = by)
                } else if (left.deleted != right.deleted) {
                    val edit = if (left.deleted) right else left
                    result[path] = edit.copy(version = maxOf(left.version, right.version) + 1)
                } else {
                    result[path] = left.copy(
                        version = maxOf(left.version, right.version) + 1,
                        blobId = "",
                        updatedBy = device,
                    )
                    val who = VaultPaths.sanitizeDevice(right.updatedBy.ifEmpty { "remote" })
                    val copy = VaultPaths.conflictName(path, who, right.version, taken)
                    taken += copy
                    result[copy] = FileEntry(
                        path = copy,
                        hash = right.hash,
                        version = 1,
                        deleted = false,
                        updatedBy = who,
                        blobId = right.blobId,
                    )
                    conflicts++
                }
            }
        }
    }
    return MergePlan(result, conflicts)
}
