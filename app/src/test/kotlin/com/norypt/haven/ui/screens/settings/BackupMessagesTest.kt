package com.norypt.haven.ui.screens.settings

import com.google.common.truth.Truth.assertThat
import com.norypt.haven.backup.RestoreResult
import org.junit.Test

/** What the backup screen says, for backups with and without photos. */
class BackupMessagesTest {
    @Test fun aFormatTwoRestoreCountsEverythingItReplaced() {
        assertThat(restoreSummary(RestoreResult.Success(reminders = 2, tasks = 3, passwords = 4, notes = 5, photos = 6)))
            .isEqualTo("Restored 2 reminders, 3 tasks, 5 notes, 4 passwords and 6 photos.")
    }

    @Test fun aFormatOneRestoreClaimsNothingAboutPhotos() {
        val text = restoreSummary(RestoreResult.Success(reminders = 1, tasks = 0, passwords = 2, notes = 0, photos = null))
        assertThat(text).isEqualTo("Restored 1 reminders, 0 tasks, 0 notes and 2 passwords. Photos already in the Vault were kept: this backup has none.")
        assertThat(text).doesNotContain("0 photos")
    }

    @Test fun aRestoreWithoutTheVaultCountsNeitherPasswordsNorPhotos() {
        assertThat(restoreSummary(RestoreResult.Success(reminders = 1, tasks = 2, passwords = null, notes = 3, photos = null)))
            .isEqualTo("Restored 1 reminders, 2 tasks and 3 notes.")
    }

    @Test fun photosWithoutAPreviewAreReported() {
        assertThat(restoreSummary(RestoreResult.Success(0, 0, 0, 0, photos = 5, photosWithoutPreview = 2)))
            .endsWith("2 photos have no preview on this phone; restore them to the gallery to open them elsewhere.")
        assertThat(restoreSummary(RestoreResult.Success(0, 0, 0, 0, photos = 1, photosWithoutPreview = 1)))
            .isEqualTo("Restored 0 reminders, 0 tasks, 0 notes, 0 passwords and 1 photo. 1 photo has no preview on this phone; restore it to the gallery to open it elsewhere.")
    }

    @Test fun theContentsLabelMentionsNotes() {
        assertThat(contentsLabel(listOf("content", "passwords", "photos"))).isEqualTo("reminders, tasks and notes, passwords, photos")
        assertThat(contentsLabel(listOf("content"))).isEqualTo("reminders, tasks and notes")
    }

    @Test fun theConfirmationOnlyPromisesToReplacePhotosWhenTheBackupHasThem() {
        assertThat(restoreConfirmBody(restoreVault = true, backupHasPhotos = true)).contains(", passwords, folders and photos on this device will be replaced")
        val formatOne = restoreConfirmBody(restoreVault = true, backupHasPhotos = false)
        assertThat(formatOne).contains(", passwords and folders on this device will be replaced")
        assertThat(formatOne).contains("This backup has no photos, so the photos in the Vault are kept.")
        val contentOnly = restoreConfirmBody(restoreVault = false, backupHasPhotos = true)
        assertThat(contentOnly).doesNotContain("photos")
        assertThat(contentOnly).doesNotContain("passwords")
    }
}
