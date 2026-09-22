package com.example.childsafetyimpl.platform.content

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Finding the people in a frame, so the cover can be placed over them instead of over everything.
 *
 * The product this app shipped until now covered the whole screen when a frame failed. That is a
 * blunt instrument and it is not what the client asked for: the reference they gave is a selective
 * effect, where the surrounding page stays readable and only the subject is obscured, with control
 * over which subjects are targeted.
 *
 * Making that possible needs a bounding box per person, which is what this class produces.
 *
 * **On the gender category, stated plainly because the product must not overclaim it.** The client
 * asked for control over "male or female content". Two facts govern what can honestly be built:
 *
 * 1. **ML Kit has no gender classifier.** Google's on-device face API returns geometry — bounding
 *    boxes, landmarks, head rotation, eyes open, smiling. It does not return gender and Google
 *    does not ship a model that does. Delivering the category at all requires bundling a
 *    separately trained network, which is what [GenderModel] is the slot for.
 * 2. **Such a model is an inference about appearance, and a weak one in exactly this setting.**
 *    Published accuracy figures come from curated frontal portrait datasets. This pipeline sees
 *    video frames: motion blur, profiles, partial faces, small faces, poor light. Accuracy is
 *    materially worse there, and it is documented as worst for darker-skinned and female faces —
 *    the direction of the error runs against the users this feature is for. Head covering, which
 *    is common in the launch market and correlates with precisely the households likeliest to
 *    switch this on, degrades it further.
 *
 * So the categories are named for what is actually measured. [SubjectCategory.APPEARS_MALE] is a
 * statement about pixels, not about a person, and nothing in the UI may present it as more. The
 * reliable control is [SubjectCategory.PERSON], which needs no gender model, and it is the one the
 * product leads with.
 */
@Singleton
class SubjectDetector(
    /**
     * Absent by default, and that is the shipping configuration.
     *
     * With no model bound, gender targeting degrades to covering every person, which is the safe
     * direction: a parent who asked for less exposure gets less exposure. Degrading the other way
     * would silently deliver no protection at all while the switch still read as on.
     */
    private val genderModel: GenderModel? = null,
) {

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                // Accurate over fast, which reverses the original choice. That choice was made
                // when covering people only happened on frames a safety classifier had already
                // failed, so a missed face meant a slightly smaller cover on a screen that was
                // being covered anyway. Covering people is now its own mode and the detector is
                // the only evidence there is: a missed face is a person shown in full behind a
                // switch that reads as on. Measured on a Pixel 4 this runs once per scan rather
                // than once per frame, and scans happen when the screen moves.
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                // Faces smaller than this share of the frame are background crowd rather than
                // subject. The frame here is a capture buffer at a third of screen resolution, so
                // this is a smaller number than it looks: a face filling a thumbnail in a six-up
                // grid is only a few percent of the whole screen, and the original value excluded
                // exactly the faces this mode exists to cover.
                .setMinFaceSize(MIN_FACE_FRACTION)
                .build()
        )
    }

    /**
     * Finds subjects worth covering.
     *
     * Returns an empty list on any failure rather than throwing. A detection failure must degrade
     * to "no regions found" and let the whole-frame verdict stand; it must never break the capture
     * loop that called it.
     */
    suspend fun detect(bitmap: Bitmap): List<DetectedSubject> {
        val faces = detectFaces(bitmap)
        if (faces.isEmpty()) return emptyList()

        return faces.map { faceBounds ->
            val category = genderModel?.classify(bitmap, faceBounds) ?: SubjectCategory.PERSON
            DetectedSubject(
                bounds = expandToBody(faceBounds, bitmap.width, bitmap.height),
                faceBounds = faceBounds,
                category = category,
                // A model that is not there has not made a low-confidence guess. It has made no
                // guess, and the difference decides whether the fallback applies.
                genderModelPresent = genderModel != null,
            )
        }
    }

    private suspend fun detectFaces(bitmap: Bitmap): List<Rect> =
        suspendCancellableCoroutine { continuation ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                detector.process(image)
                    .addOnSuccessListener { faces ->
                        if (continuation.isActive) {
                            continuation.resume(faces.map { it.boundingBox })
                        }
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(emptyList())
            }
        }

    /**
     * Grows a face box into something worth covering.
     *
     * A face detector returns the face. Covering only that produces the well-known and faintly
     * absurd result of a floating blurred head above a fully visible body, which does not deliver
     * what this feature is for. The multipliers below approximate head-and-torso from head size:
     * wider than the face because shoulders are, and much taller downwards than upwards because
     * there is a body below the face and only hair above it.
     *
     * Deliberately geometric rather than segmented. ML Kit's selfie segmentation produces a far
     * better mask and costs several times more per frame, and the exposure window is the metric
     * this feature is judged on. Segmentation is the right upgrade once a purpose-trained
     * classifier replaces the proxy, and it is recorded as such in the POC results.
     */
    private fun expandToBody(face: Rect, frameWidth: Int, frameHeight: Int): Rect {
        val width = face.width()
        val height = face.height()
        return Rect(
            (face.left - width * SIDE_EXPANSION).roundToInt().coerceAtLeast(0),
            (face.top - height * ABOVE_EXPANSION).roundToInt().coerceAtLeast(0),
            (face.right + width * SIDE_EXPANSION).roundToInt().coerceAtMost(frameWidth),
            (face.bottom + height * BELOW_EXPANSION).roundToInt().coerceAtMost(frameHeight),
        )
    }

    companion object {
        private const val MIN_FACE_FRACTION = 0.035f
        private const val SIDE_EXPANSION = 1.1f
        private const val ABOVE_EXPANSION = 0.6f
        private const val BELOW_EXPANSION = 3.2f
    }
}

/** One subject found in a frame, and what is known about it. */
data class DetectedSubject(
    /** The region to cover: head and torso, not just the face. */
    val bounds: Rect,
    /** The face itself, kept because a gender model needs the crop rather than the body box. */
    val faceBounds: Rect,
    val category: SubjectCategory,
    val genderModelPresent: Boolean,
) {

    /**
     * Whether this subject should be covered, given what the parent selected.
     *
     * The fallback in the middle is the important line. If a parent asked to cover one appearance
     * category and nothing on the device can tell the categories apart, the choice is between
     * covering every person and covering none. Covering every person over-delivers on a request
     * the parent actually made; covering none silently delivers nothing while the switch still
     * reads as on. For a values filter the first is plainly the right failure direction, and it is
     * a decision rather than an accident, so it is written here where it can be argued with.
     */
    fun shouldCover(selected: Set<SubjectCategory>): Boolean = when {
        SubjectCategory.PERSON in selected -> true
        !genderModelPresent && selected.any { it.isAppearanceBased } -> true
        else -> category in selected
    }
}

/**
 * What a detected subject is taken to be.
 *
 * The naming is deliberate and should survive review. "Appears male" is what an appearance
 * classifier can support; "male" is a claim about a person that no model on a phone is entitled to
 * make about a stranger in a video frame.
 */
enum class SubjectCategory(
    val label: String,
    /** The parent-facing description, which states the reliability rather than implying it. */
    val description: String,
    val isAppearanceBased: Boolean,
) {
    PERSON(
        label = "Anyone",
        description = "Covers every person detected in the picture. This is the reliable " +
            "setting: it needs only face detection, which works well across skin tones, head " +
            "coverings and ages.",
        isAppearanceBased = false,
    ),
    APPEARS_MALE(
        label = "People who appear male",
        description = "Best effort. Judged from appearance by a bundled model, and it will be " +
            "wrong sometimes, particularly on side views, partial faces, children and covered " +
            "faces.",
        isAppearanceBased = true,
    ),
    APPEARS_FEMALE(
        label = "People who appear female",
        description = "Best effort, with the same limits. Published accuracy for this kind of " +
            "model is lowest on exactly these cases, so expect both misses and mistakes.",
        isAppearanceBased = true,
    ),
    UNKNOWN(
        label = "Not determined",
        description = "A person was found but their appearance could not be categorised.",
        isAppearanceBased = false,
    ),
}

/**
 * The slot a bundled appearance classifier drops into.
 *
 * Nothing implements this today, on purpose. Shipping the category with no model behind it and a
 * switch that appears to work would be the dishonest option; shipping the reliable
 * [SubjectCategory.PERSON] control and an empty, clearly-named slot is the honest one.
 *
 * Whatever implements it must run entirely on the device. A face crop of a child's screen leaving
 * the handset is a different product with different disclosures, and no accuracy gain justifies it.
 */
interface GenderModel {

    /** Describes the model, so a screen can state what is behind a verdict. */
    val identity: ModelIdentity

    /**
     * Classifies the face inside [faceBounds].
     *
     * Implementations must return [SubjectCategory.UNKNOWN] rather than guessing when confidence
     * is low. A forced coin-flip between two categories is worse than admitting ignorance,
     * because the fallback in [DetectedSubject.shouldCover] can act safely on UNKNOWN and cannot
     * act at all on a confident wrong answer.
     */
    suspend fun classify(bitmap: Bitmap, faceBounds: Rect): SubjectCategory
}
