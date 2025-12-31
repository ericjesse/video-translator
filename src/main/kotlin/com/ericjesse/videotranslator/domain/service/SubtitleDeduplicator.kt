package com.ericjesse.videotranslator.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Service for deduplicating subtitle entries from various sources.
 *
 * Both YouTube VTT captions and Whisper transcriptions often produce
 * duplicate/overlapping text segments. This service provides unified
 * deduplication logic for both sources.
 *
 * Common issues handled:
 * - Phantom segments: Very short segments (<100ms) followed by longer segments
 *   containing the same text
 * - Text overlap: End of one segment repeated at the start of the next
 * - Near-duplicate segments: Consecutive segments with nearly identical text
 */
class SubtitleDeduplicator {

    /**
     * Represents a timed text entry for deduplication.
     * Generic interface to work with both SubtitleEntry and WhisperSegment.
     */
    data class TimedText(
        val startTime: Long,
        val endTime: Long,
        val text: String
    ) {
        val duration: Long get() = endTime - startTime
    }

    /**
     * Result of deduplication with original indices preserved.
     */
    data class DeduplicationResult<T>(
        val entries: List<T>,
        val originalCount: Int,
        val removedCount: Int
    )

    /**
     * Deduplicates a list of timed text entries.
     *
     * @param entries Original list of entries
     * @param toTimedText Function to extract TimedText from entry
     * @param updateText Function to create new entry with updated text
     * @param reindex Function to create new entry with updated index
     * @return Deduplicated list
     */
    fun <T> deduplicate(
        entries: List<T>,
        toTimedText: (T) -> TimedText,
        updateText: (T, String) -> T,
        reindex: (T, Int) -> T
    ): DeduplicationResult<T> {
        if (entries.size < 2) {
            return DeduplicationResult(entries, entries.size, 0)
        }

        logger.debug { "Deduplicating ${entries.size} subtitle entries..." }

        val deduplicated = mutableListOf<T>()
        var i = 0

        while (i < entries.size) {
            val current = entries[i]
            val currentTimed = toTimedText(current)

            // Look ahead to check if this is a phantom segment
            if (i + 1 < entries.size) {
                val next = entries[i + 1]
                val nextTimed = toTimedText(next)

                // Check if current is a short phantom segment
                if (isPhantomSegment(currentTimed, nextTimed)) {
                    logger.debug { "Skipping phantom subtitle: ${currentTimed.duration}ms '${currentTimed.text.take(30)}...'" }
                    i++
                    continue
                }

                // Check for text overlap between segments
                val overlapWords = findOverlapWordCount(currentTimed.text, nextTimed.text)
                if (overlapWords > 0) {
                    deduplicated.add(current)
                    val cleanedText = removeOverlappingPrefix(nextTimed.text, overlapWords)
                    if (cleanedText.isNotBlank()) {
                        deduplicated.add(updateText(next, cleanedText))
                    }
                    i += 2
                    continue
                }
            }

            deduplicated.add(current)
            i++
        }

        // Merge consecutive near-duplicates
        val merged = mergeNearDuplicates(deduplicated, toTimedText, updateText)

        // Reindex entries
        val reindexed = merged.mapIndexed { idx, entry -> reindex(entry, idx + 1) }

        val removedCount = entries.size - reindexed.size
        logger.debug { "After deduplication: ${reindexed.size} entries (removed $removedCount)" }

        return DeduplicationResult(reindexed, entries.size, removedCount)
    }

    /**
     * Checks if current segment is a "phantom" - a short segment whose text
     * is contained in the following longer segment.
     */
    private fun isPhantomSegment(current: TimedText, next: TimedText): Boolean {
        // Phantom criteria: very short (<100ms) or much shorter than next
        val isShort = current.duration < 100 ||
                (current.duration < 500 && next.duration > current.duration * 3)

        return isShort && isTextContainedIn(current.text, next.text)
    }

    /**
     * Checks if shortText is fully contained at the start of longText.
     */
    fun isTextContainedIn(shortText: String, longText: String): Boolean {
        val shortWords = shortText.trim().lowercase().split(Regex("\\s+"))
        val longWords = longText.trim().lowercase().split(Regex("\\s+"))

        if (shortWords.isEmpty() || shortWords.size > longWords.size) return false

        return shortWords.indices.all { idx ->
            wordsMatch(shortWords[idx], longWords[idx])
        }
    }

    /**
     * Finds how many words overlap between end of prevText and start of nextText.
     */
    fun findOverlapWordCount(prevText: String, nextText: String): Int {
        val prevWords = prevText.trim().split(Regex("\\s+"))
        val nextWords = nextText.trim().split(Regex("\\s+"))

        if (prevWords.isEmpty() || nextWords.isEmpty()) return 0

        var maxOverlap = 0
        val maxCheck = minOf(prevWords.size, nextWords.size, 15)

        for (overlapLen in 1..maxCheck) {
            val prevSuffix = prevWords.takeLast(overlapLen)
            val nextPrefix = nextWords.take(overlapLen)

            if (prevSuffix.indices.all { idx -> wordsMatch(prevSuffix[idx], nextPrefix[idx]) }) {
                maxOverlap = overlapLen
            }
        }

        return maxOverlap
    }

    /**
     * Removes the first N words from text.
     */
    fun removeOverlappingPrefix(text: String, wordCount: Int): String {
        val words = text.trim().split(Regex("\\s+"))
        return words.drop(wordCount).joinToString(" ")
    }

    /**
     * Compares two words, ignoring case, punctuation, and accents.
     * Supports French characters (àâäéèêëïîôùûüç).
     */
    fun wordsMatch(a: String, b: String): Boolean {
        val cleanA = a.lowercase().replace(Regex("[^a-z0-9àâäéèêëïîôùûüç]"), "")
        val cleanB = b.lowercase().replace(Regex("[^a-z0-9àâäéèêëïîôùûüç]"), "")
        return cleanA == cleanB
    }

    /**
     * Merges consecutive segments with identical or nearly identical text.
     */
    private fun <T> mergeNearDuplicates(
        entries: List<T>,
        toTimedText: (T) -> TimedText,
        updateText: (T, String) -> T
    ): List<T> {
        if (entries.size < 2) return entries

        val merged = mutableListOf<T>()
        var i = 0

        while (i < entries.size) {
            var current = entries[i]
            var currentTimed = toTimedText(current)

            // Look ahead for duplicates
            while (i + 1 < entries.size) {
                val next = entries[i + 1]
                val nextTimed = toTimedText(next)

                if (textsAreSimilar(currentTimed.text, nextTimed.text)) {
                    // Keep the longer text
                    if (nextTimed.text.length > currentTimed.text.length) {
                        current = next
                        currentTimed = nextTimed
                    }
                    i++
                } else {
                    break
                }
            }

            merged.add(current)
            i++
        }

        return merged
    }

    /**
     * Checks if two texts are similar enough to be considered duplicates.
     * Uses 80% word match threshold.
     */
    fun textsAreSimilar(text1: String, text2: String): Boolean {
        val words1 = text1.trim().lowercase().split(Regex("\\s+"))
        val words2 = text2.trim().lowercase().split(Regex("\\s+"))

        if (words1.isEmpty() || words2.isEmpty()) return false

        val shorter = if (words1.size <= words2.size) words1 else words2
        val longer = if (words1.size <= words2.size) words2 else words1

        val matchingPrefix = shorter.indices.count { idx ->
            idx < longer.size && wordsMatch(shorter[idx], longer[idx])
        }

        return matchingPrefix >= shorter.size * 0.8
    }
}
