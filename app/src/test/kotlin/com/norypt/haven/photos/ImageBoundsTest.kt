package com.norypt.haven.photos

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageBoundsTest {
    @Test fun normalPhotoAccepted() { assertThat(ImageBounds.check(4000, 3000)).isTrue() }
    @Test fun hundredMegapixelsIsTheLimit() {
        assertThat(ImageBounds.check(10_000, 10_000)).isTrue()
        assertThat(ImageBounds.check(10_001, 10_000)).isFalse()
    }
    @Test fun zeroOrNegativeRefused() { assertThat(ImageBounds.check(0, 10)).isFalse(); assertThat(ImageBounds.check(10, -1)).isFalse() }
    @Test fun hugeDimensionsDoNotOverflow() { assertThat(ImageBounds.check(Int.MAX_VALUE, Int.MAX_VALUE)).isFalse() }
    @Test fun sampleSizeIsAPowerOfTwoThatFits() {
        assertThat(ImageBounds.sampleSize(4000, 3000, 512)).isEqualTo(4)   // 4000/4 = 1000 ≥ 512, 4000/8 = 500 < 512
        assertThat(ImageBounds.sampleSize(400, 300, 512)).isEqualTo(1)
    }
    @Test fun fitKeepsAspect() { assertThat(ImageBounds.fit(4000, 3000, 512)).isEqualTo(512 to 384) }
    @Test fun fitNeverUpscales() { assertThat(ImageBounds.fit(100, 50, 512)).isEqualTo(100 to 50) }
}
