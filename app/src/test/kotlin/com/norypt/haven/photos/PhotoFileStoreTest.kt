package com.norypt.haven.photos

import com.google.common.truth.Truth.assertThat
import com.norypt.haven.crypto.PhotoCipher.Kind
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhotoFileStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun store() = PhotoFileStore(File(tmp.root, "photos"))

    @Test fun writeThenOpen() {
        val s = store(); val id = s.newFileId()
        s.write(id, Kind.ORIGINAL) { it.write(byteArrayOf(1, 2, 3)) }
        assertThat(s.open(id, Kind.ORIGINAL).readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(s.dir.list()!!.toList()).containsExactly(id)
    }

    @Test fun thumbnailIsASeparateFile() {
        val s = store(); val id = s.newFileId()
        s.write(id, Kind.THUMBNAIL) { it.write(1) }
        assertThat(s.dir.list()!!.toList()).containsExactly("$id.t")
    }

    @Test fun failedWriteLeavesNothing() {
        val s = store(); val id = s.newFileId()
        runCatching { s.write(id, Kind.ORIGINAL) { it.write(1); error("boom") } }
        assertThat(s.dir.list()!!.toList()).isEmpty()
    }

    @Test fun fileIdsAreRandomHex() {
        val s = store()
        val a = s.newFileId()
        assertThat(a).matches("[0-9a-f]{32}")
        assertThat(a).isNotEqualTo(s.newFileId())
    }

    @Test fun reconcileRemovesOrphansAndTemps() {
        val s = store()
        val keep = s.newFileId(); val orphan = s.newFileId()
        s.write(keep, Kind.ORIGINAL) { it.write(1) }; s.write(keep, Kind.THUMBNAIL) { it.write(1) }
        s.write(orphan, Kind.ORIGINAL) { it.write(1) }
        File(s.dir, "${s.newFileId()}.tmp").writeText("x")
        File(tmp.root, "photos.del-abc").apply { mkdirs(); File(this, "x").writeText("x") }
        val removed = s.reconcile(setOf(keep))
        assertThat(removed).isEqualTo(3)
        assertThat(s.dir.list()!!.toList()).containsExactly(keep, "$keep.t")
        assertThat(File(tmp.root, "photos.del-abc").exists()).isFalse()
    }

    @Test fun deleteRemovesBothKinds() {
        val s = store(); val id = s.newFileId()
        s.write(id, Kind.ORIGINAL) { it.write(1) }; s.write(id, Kind.THUMBNAIL) { it.write(1) }
        s.delete(id)
        assertThat(s.dir.list()!!.toList()).isEmpty()
    }

    @Test fun retireAsyncMovesTheDirectoryAwayImmediately() {
        val s = store(); s.write(s.newFileId(), Kind.ORIGINAL) { it.write(1) }
        PhotoFileStore.retireAsync(s.dir)
        assertThat(s.dir.exists()).isFalse()
    }
}
