package com.touvay.engine.models

import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal interface StorageDurability {
    fun forceFile(path: Path)

    fun forceDirectory(path: Path)

    fun commitDirectory(source: Path, target: Path)

    fun replaceFile(source: Path, target: Path)
}

internal object NioStorageDurability : StorageDurability {
    override fun forceFile(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
    }

    override fun forceDirectory(path: Path) {
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }

    override fun commitDirectory(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // installed.ok is the commit authority if the filesystem cannot atomically rename dirs.
            Files.move(source, target)
        }
        forceDirectory(target.parent)
    }

    override fun replaceFile(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            modelStoreFailure(ModelStoreFailure.ATOMIC_MOVE_UNSUPPORTED)
        }
        forceDirectory(target.parent)
    }
}
