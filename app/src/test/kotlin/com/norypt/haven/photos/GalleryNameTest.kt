package com.norypt.haven.photos

import com.google.common.truth.Truth.assertThat
import com.norypt.haven.ui.screens.photos.GalleryExport
import org.junit.Test

class GalleryNameTest {
    @Test fun nameUsesTheTypeExtension() {
        val t = java.time.LocalDateTime.of(2026, 10, 12, 9, 5, 7).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertThat(GalleryExport.fileName("image/jpeg", t, 0)).isEqualTo("haven-20261012-090507-0.jpg")
        assertThat(GalleryExport.fileName("image/heic", t, 3)).isEqualTo("haven-20261012-090507-3.heic")
        assertThat(GalleryExport.fileName("application/x-odd", t, 1)).isEqualTo("haven-20261012-090507-1.img")
    }
}
