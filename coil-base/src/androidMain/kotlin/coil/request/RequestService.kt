package coil.request

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.lifecycle.Lifecycle
import coil.Extras
import coil.ImageLoader
import coil.drawable
import coil.memory.MemoryCache
import coil.size.Dimension
import coil.size.Scale
import coil.size.Size
import coil.target.Target
import coil.target.ViewTarget
import coil.transform.Transformation
import coil.util.HardwareBitmapService
import coil.util.Logger
import coil.util.SystemCallbacks
import coil.util.VALID_TRANSFORMATION_CONFIGS
import coil.util.allowInexactSize
import coil.util.getLifecycle
import coil.util.isHardware
import coil.util.safeConfig
import kotlinx.coroutines.Job

internal actual fun RequestService(
    imageLoader: ImageLoader,
    systemCallbacks: SystemCallbacks,
    logger: Logger?,
): RequestService = AndroidRequestService(imageLoader, systemCallbacks, logger)

/** Handles operations that act on [ImageRequest]s. */
internal class AndroidRequestService(
    private val imageLoader: ImageLoader,
    private val systemCallbacks: SystemCallbacks,
    logger: Logger?,
): RequestService {
    private val hardwareBitmapService = HardwareBitmapService(logger)

    /**
     * Wrap [request] to automatically dispose and/or restart the [ImageRequest]
     * based on its lifecycle.
     */
    override fun requestDelegate(request: ImageRequest, job: Job): RequestDelegate {
        val lifecycle = request.resolveLifecycle()
        val target = request.target
        if (target is ViewTarget<*>) {
            return ViewTargetRequestDelegate(imageLoader, request, target, lifecycle, job)
        } else {
            return BaseRequestDelegate(lifecycle, job)
        }
    }

    private fun ImageRequest.resolveLifecycle(): Lifecycle {
        val target = target
        val context = if (target is ViewTarget<*>) target.view.context else context
        return context.getLifecycle() ?: GlobalLifecycle
    }

    override fun errorResult(request: ImageRequest, throwable: Throwable): ErrorResult {
        return commonErrorResult(request, throwable)
    }

    /**
     * Return the request options. The function is called from the main thread and must be fast.
     */
    override fun options(request: ImageRequest, size: Size): Options {
        var extras = request.extras

        // Fall back to ARGB_8888 if the requested bitmap config does not pass the checks.
        val isValidConfig = isConfigValidForTransformations(request) &&
            isConfigValidForHardwareAllocation(request, size)
        val config = if (isValidConfig) {
            request.bitmapConfig
        } else {
            Bitmap.Config.ARGB_8888
        }

        // Disable fetching from the network if we know we're offline.
        val networkCachePolicy = if (systemCallbacks.isOnline) {
            request.networkCachePolicy
        } else {
            CachePolicy.DISABLED
        }

        // Use `Scale.FIT` if either dimension is undefined.
        val scale = if (size.width == Dimension.Undefined || size.height == Dimension.Undefined) {
            Scale.FIT
        } else {
            request.scale
        }

        // Disable allowRgb565 if there are transformations or the requested config is ALPHA_8.
        // ALPHA_8 is a mask config where each pixel is 1 byte so it wouldn't make sense to use
        // RGB_565 as an optimization in that case.
        val allowRgb565 = request.allowRgb565 &&
            request.transformations.isEmpty() &&
            config != Bitmap.Config.ALPHA_8
        if (allowRgb565 != request.allowRgb565) {
            extras = request.extras.newBuilder()
                .set(Extras.Key.allowRgb565, allowRgb565)
                .build()
        }

        return Options(
            context = request.context,
            size = size,
            scale = scale,
            allowInexactSize = request.allowInexactSize,
            diskCacheKey = request.diskCacheKey,
            memoryCachePolicy = request.memoryCachePolicy,
            diskCachePolicy = request.diskCachePolicy,
            networkCachePolicy = networkCachePolicy,
            extras = extras,
        )
    }

    override fun updateOptionsOnWorkerThread(options: Options): Options {
        if (allowHardwareWorkerThread(options)) {
            return options
        } else {
            return options.copy(
                extras = options.extras.newBuilder()
                    .set(Extras.Key.bitmapConfig, Bitmap.Config.ARGB_8888)
                    .build(),
            )
        }
    }

    /**
     * Return 'true' if [cacheValue] is a valid (i.e. can be returned to its [Target])
     * config for [request].
     */
    override fun isCacheValueValidForHardware(
        request: ImageRequest,
        cacheValue: MemoryCache.Value,
    ): Boolean {
        val drawable = cacheValue.image.drawable as? BitmapDrawable ?: return true
        val requestedConfig = drawable.bitmap.safeConfig
        return isConfigValidForHardware(request, requestedConfig)
    }

    /**
     * Return 'true' if [requestedConfig] is a valid (i.e. can be returned to its [Target])
     * config for [request].
     */
    fun isConfigValidForHardware(
        request: ImageRequest,
        requestedConfig: Bitmap.Config,
    ): Boolean {
        // Short circuit if the requested bitmap config is software.
        if (!requestedConfig.isHardware) {
            return true
        }

        // Ensure the request allows hardware bitmaps.
        if (!request.allowHardware) {
            return false
        }

        // Prevent hardware bitmaps for non-hardware accelerated targets.
        val target = request.target
        if (target is ViewTarget<*> &&
            target.view.run { isAttachedToWindow && !isHardwareAccelerated }) {
            return false
        }

        return true
    }

    /** Return 'true' if we can allocate a hardware bitmap. */
    fun allowHardwareWorkerThread(options: Options): Boolean {
        return !options.bitmapConfig.isHardware || hardwareBitmapService.allowHardwareWorkerThread()
    }

    /**
     * Return 'true' if [request]'s requested bitmap config is valid (i.e. can be returned
     * to its [Target]).
     *
     * This check is similar to [isCacheValueValidForHardware] except this method also checks
     * that we are able to allocate a new hardware bitmap.
     */
    private fun isConfigValidForHardwareAllocation(request: ImageRequest, size: Size): Boolean {
        // Short circuit if the requested bitmap config is software.
        if (!request.bitmapConfig.isHardware) {
            return true
        }

        return isConfigValidForHardware(request, request.bitmapConfig) &&
            hardwareBitmapService.allowHardwareMainThread(size)
    }

    /** Return 'true' if [ImageRequest.bitmapConfig] is valid given its [Transformation]s. */
    private fun isConfigValidForTransformations(request: ImageRequest): Boolean {
        return request.transformations.isEmpty() ||
            request.bitmapConfig in VALID_TRANSFORMATION_CONFIGS
    }
}
