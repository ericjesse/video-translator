package com.ericjesse.videotranslator.domain.pipeline

import com.ericjesse.videotranslator.domain.model.Subtitles
import com.ericjesse.videotranslator.domain.model.TranslationJob
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/**
 * Manages pipeline checkpoints for resume functionality.
 *
 * Checkpoints allow the translation pipeline to be resumed from a specific stage
 * if it fails or is cancelled. This is particularly useful for long videos where
 * re-downloading or re-transcribing would be wasteful.
 *
 * Features:
 * - Save checkpoints after each major stage
 * - Load checkpoints for resuming
 * - List available checkpoints
 * - Delete checkpoints after successful completion
 *
 * @param checkpointDir Directory for storing checkpoint files.
 */
class CheckpointManager(
    private val checkpointDir: File = File(System.getProperty("user.home"), ".video-translator/checkpoints"),
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    init {
        checkpointDir.mkdirs()
    }

    /**
     * Saves a checkpoint for the given job.
     *
     * @param jobId Unique identifier for the job.
     * @param stage The last completed pipeline stage.
     * @param videoPath Path to the downloaded video file, or null.
     * @param subtitles The transcribed subtitles, or null.
     * @param translatedSubtitles The translated subtitles, or null.
     * @param job The translation job being processed.
     * @return The saved checkpoint, or null if save failed.
     */
    fun saveCheckpoint(
        jobId: String,
        stage: PipelineStageName,
        videoPath: String?,
        subtitles: Subtitles?,
        translatedSubtitles: Subtitles?,
        job: TranslationJob,
    ): PipelineCheckpoint? {
        return try {
            val checkpoint = PipelineCheckpoint(
                jobId = jobId,
                lastCompletedStage = stage,
                downloadedVideoPath = videoPath,
                subtitles = subtitles,
                translatedSubtitles = translatedSubtitles,
                videoInfo = job.videoInfo,
                targetLanguage = job.targetLanguage,
                outputOptions = job.outputOptions
            )

            val checkpointFile = getCheckpointFile(jobId)
            checkpointFile.writeText(json.encodeToString(checkpoint))

            logger.debug { "Checkpoint saved for stage: $stage at ${checkpointFile.absolutePath}" }
            checkpoint
        } catch (e: Exception) {
            logger.warn { "Failed to save checkpoint: ${e.message}" }
            null
        }
    }

    /**
     * Loads a checkpoint for the given job.
     *
     * @param jobId The job ID to load.
     * @return The checkpoint if found and valid, null otherwise.
     */
    fun loadCheckpoint(jobId: String): PipelineCheckpoint? {
        return try {
            val checkpointFile = getCheckpointFile(jobId)
            if (!checkpointFile.exists()) return null

            val checkpoint: PipelineCheckpoint = json.decodeFromString(checkpointFile.readText())
            if (checkpoint.isValid()) checkpoint else null
        } catch (e: Exception) {
            logger.warn { "Failed to load checkpoint: ${e.message}" }
            null
        }
    }

    /**
     * Deletes a checkpoint for the given job.
     *
     * @param jobId The job ID whose checkpoint should be deleted.
     * @return true if deleted successfully, false otherwise.
     */
    fun deleteCheckpoint(jobId: String): Boolean {
        return try {
            val checkpointFile = getCheckpointFile(jobId)
            if (checkpointFile.exists()) {
                checkpointFile.delete()
                logger.debug { "Checkpoint deleted: $jobId" }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            logger.warn { "Failed to delete checkpoint: ${e.message}" }
            false
        }
    }

    /**
     * Lists all available checkpoints.
     *
     * @return List of valid checkpoint job IDs.
     */
    fun listCheckpoints(): List<String> {
        return checkpointDir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val checkpoint: PipelineCheckpoint = json.decodeFromString(file.readText())
                    if (checkpoint.isValid()) checkpoint.jobId else null
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()
    }

    /**
     * Gets all valid checkpoints with full details.
     *
     * @return List of valid checkpoints.
     */
    fun getAllCheckpoints(): List<PipelineCheckpoint> {
        return checkpointDir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val checkpoint: PipelineCheckpoint = json.decodeFromString(file.readText())
                    if (checkpoint.isValid()) checkpoint else null
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()
    }

    /**
     * Checks if a checkpoint exists for the given job.
     *
     * @param jobId The job ID to check.
     * @return true if a valid checkpoint exists.
     */
    fun hasCheckpoint(jobId: String): Boolean {
        return loadCheckpoint(jobId) != null
    }

    /**
     * Cleans up old or invalid checkpoints.
     *
     * @param maxAgeDays Maximum age of checkpoints to keep (default: 7 days).
     * @return Number of checkpoints deleted.
     */
    fun cleanupOldCheckpoints(maxAgeDays: Int = 7): Int {
        val cutoffTime = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        var deletedCount = 0

        checkpointDir.listFiles { file -> file.extension == "json" }?.forEach { file ->
            try {
                val checkpoint: PipelineCheckpoint = json.decodeFromString(file.readText())
                if (!checkpoint.isValid() || checkpoint.timestamp < cutoffTime) {
                    file.delete()
                    deletedCount++
                    logger.debug { "Deleted old/invalid checkpoint: ${checkpoint.jobId}" }
                }
            } catch (e: Exception) {
                // Invalid file, delete it
                file.delete()
                deletedCount++
            }
        }

        if (deletedCount > 0) {
            logger.info { "Cleaned up $deletedCount old/invalid checkpoints" }
        }
        return deletedCount
    }

    /**
     * Gets the checkpoint file for a job ID.
     */
    private fun getCheckpointFile(jobId: String): File {
        return File(checkpointDir, "$jobId.json")
    }

    /**
     * Gets the checkpoint directory path.
     */
    fun getCheckpointDirectory(): File = checkpointDir
}
