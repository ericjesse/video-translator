package com.ericjesse.videotranslator.infrastructure.config.serializers

import com.ericjesse.videotranslator.domain.model.Language
import com.ericjesse.videotranslator.domain.model.SubtitleType
import com.ericjesse.videotranslator.domain.model.TranslationService
import com.ericjesse.videotranslator.domain.model.WhisperModel
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializer for WhisperModel enum that uses the model name as the serialized value.
 * Example: WhisperModel.BASE serializes to "base"
 */
object WhisperModelSerializer : KSerializer<WhisperModel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("WhisperModel", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: WhisperModel) {
        encoder.encodeString(value.modelName)
    }

    override fun deserialize(decoder: Decoder): WhisperModel {
        val modelName = decoder.decodeString()
        return WhisperModel.fromModelName(modelName) ?: WhisperModel.BASE
    }
}

/**
 * Serializer for TranslationService enum that uses lowercase string names.
 * Example: TranslationService.LIBRE_TRANSLATE serializes to "libretranslate"
 */
object TranslationServiceSerializer : KSerializer<TranslationService> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TranslationService", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: TranslationService) {
        encoder.encodeString(value.toConfigString())
    }

    override fun deserialize(decoder: Decoder): TranslationService {
        val value = decoder.decodeString()
        return TranslationService.fromString(value) ?: TranslationService.LIBRE_TRANSLATE
    }
}

/**
 * Serializer for Language enum that uses the language code.
 * Example: Language.ENGLISH serializes to "en"
 */
object LanguageSerializer : KSerializer<Language> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Language", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Language) {
        encoder.encodeString(value.code)
    }

    override fun deserialize(decoder: Decoder): Language {
        val code = decoder.decodeString()
        return Language.fromCode(code) ?: Language.ENGLISH
    }
}

/**
 * Serializer for nullable Language that uses the language code or null.
 */
object NullableLanguageSerializer : KSerializer<Language?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NullableLanguage", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Language?) {
        if (value != null) {
            encoder.encodeString(value.code)
        } else {
            encoder.encodeString("")
        }
    }

    override fun deserialize(decoder: Decoder): Language? {
        val code = decoder.decodeString()
        return if (code.isBlank()) null else Language.fromCode(code)
    }
}

/**
 * Serializer for SubtitleType enum that uses lowercase string names.
 * Example: SubtitleType.BURNED_IN serializes to "burned_in"
 */
object SubtitleTypeSerializer : KSerializer<SubtitleType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SubtitleType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SubtitleType) {
        encoder.encodeString(value.toConfigString())
    }

    override fun deserialize(decoder: Decoder): SubtitleType {
        val value = decoder.decodeString()
        return SubtitleType.fromConfigString(value) ?: SubtitleType.BURNED_IN
    }
}
