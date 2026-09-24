package com.norypt.haven

import com.google.common.truth.Truth.assertThat
import com.norypt.haven.backup.BackupManager
import org.junit.Test

class RestorableSettingTest {
    @Test fun aBackupCanNeverReplaceTheVaultsOwnBackupKey() {
        assertThat(BackupManager.isRestorableSetting(BackupManager.META_BACKUP_KEY, "00".repeat(32))).isFalse()
        assertThat(BackupManager.isRestorableSetting(BackupManager.META_BACKUP_KEY_VERIFIED, "1")).isFalse()
    }

    @Test fun ordinarySettingsWithinBoundsAreRestored() {
        assertThat(BackupManager.isRestorableSetting("alarm.defaultTime", "09:00")).isTrue()
        assertThat(BackupManager.isRestorableSetting("k".repeat(65), "v")).isFalse()
        assertThat(BackupManager.isRestorableSetting("k", "v".repeat(4097))).isFalse()
    }
}
