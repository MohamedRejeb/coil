package coil3.test.utils

import coil3.Image
import org.jetbrains.skia.Canvas

actual class FakeImage actual constructor(
    override val width: Int,
    override val height: Int,
    override val size: Long,
    override val shareable: Boolean,
) : Image {
    override fun Canvas.onDraw() = Unit
}
