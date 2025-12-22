@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.settings.tabs

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.infrastructure.config.AppSettings
import com.ericjesse.videotranslator.infrastructure.config.TranslationServiceConfig
import com.ericjesse.videotranslator.ui.components.AppButton
import com.ericjesse.videotranslator.ui.components.ButtonSize
import com.ericjesse.videotranslator.ui.components.ButtonStyle
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.theme.AppColors
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.launch

/**
 * Available translation services.
 */
enum class TranslationServiceOption(
    val id: String,
    val displayName: String,
    val description: String,
    val icon: ImageVector,
    val requiresApiKey: Boolean
) {
    LIBRE_TRANSLATE(
        id = "libretranslate",
        displayName = "LibreTranslate",
        description = "Free, open-source translation",
        icon = Icons.Default.Public,
        requiresApiKey = false
    ),
    DEEPL(
        id = "deepl",
        displayName = "DeepL",
        description = "High-quality neural translation",
        icon = Icons.Default.Translate,
        requiresApiKey = true
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        description = "GPT-powered translation with context",
        icon = Icons.Default.Psychology,
        requiresApiKey = true
    );

    companion object {
        fun fromId(id: String): TranslationServiceOption? =
            entries.find { it.id == id }
    }
}

/**
 * Connection test result states.
 */
sealed class ConnectionTestResult {
    data object Idle : ConnectionTestResult()
    data object Testing : ConnectionTestResult()
    data class Success(val message: String) : ConnectionTestResult()
    data class Error(val message: String) : ConnectionTestResult()
}

/**
 * Translation settings tab content.
 *
 * Allows configuration of:
 * - Active translation service selection
 * - LibreTranslate instance URL and connection testing
 * - DeepL API key
 * - OpenAI API key
 *
 * @param appModule Application module for accessing services.
 * @param settings Current application settings.
 * @param serviceConfig Current service configuration.
 * @param onUpdateSettings Callback to update settings.
 * @param onUpdateServiceConfig Callback to update service configuration.
 * @param modifier Modifier for the content.
 */
@Composable
fun TranslationTabContent(
    appModule: AppModule,
    settings: AppSettings,
    serviceConfig: TranslationServiceConfig,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onUpdateServiceConfig: ((TranslationServiceConfig) -> TranslationServiceConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val i18n = appModule.i18nManager
    val scope = rememberCoroutineScope()

    // Connection test state
    var libreTranslateTestResult by remember { mutableStateOf<ConnectionTestResult>(ConnectionTestResult.Idle) }
    var deeplTestResult by remember { mutableStateOf<ConnectionTestResult>(ConnectionTestResult.Idle) }
    var openaiTestResult by remember { mutableStateOf<ConnectionTestResult>(ConnectionTestResult.Idle) }

    val activeService = TranslationServiceOption.fromId(settings.translation.defaultService)
        ?: TranslationServiceOption.LIBRE_TRANSLATE

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // Active Service Selection
        ActiveServiceSection(
            i18n = i18n,
            selectedService = activeService,
            onServiceSelected = { service ->
                onUpdateSettings {
                    it.copy(translation = it.translation.copy(defaultService = service.id))
                }
            }
        )

        // LibreTranslate Settings (always visible when selected)
        AnimatedVisibility(
            visible = activeService == TranslationServiceOption.LIBRE_TRANSLATE,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            LibreTranslateSettingsCard(
                i18n = i18n,
                instanceUrl = serviceConfig.libreTranslateUrl ?: "",
                testResult = libreTranslateTestResult,
                onInstanceUrlChange = { url ->
                    onUpdateServiceConfig { it.copy(libreTranslateUrl = url.ifEmpty { null }) }
                    libreTranslateTestResult = ConnectionTestResult.Idle
                },
                onTestConnection = {
                    scope.launch {
                        libreTranslateTestResult = ConnectionTestResult.Testing
                        libreTranslateTestResult = testLibreTranslateConnection(
                            appModule.httpClient,
                            serviceConfig.libreTranslateUrl ?: ""
                        )
                    }
                }
            )
        }

        // Other Services Section (API Keys)
        OtherServicesSection(
            i18n = i18n,
            deeplApiKey = serviceConfig.deeplApiKey ?: "",
            openaiApiKey = serviceConfig.openaiApiKey ?: "",
            deeplTestResult = deeplTestResult,
            openaiTestResult = openaiTestResult,
            onDeeplApiKeyChange = { key ->
                onUpdateServiceConfig { it.copy(deeplApiKey = key.ifEmpty { null }) }
                deeplTestResult = ConnectionTestResult.Idle
            },
            onOpenaiApiKeyChange = { key ->
                onUpdateServiceConfig { it.copy(openaiApiKey = key.ifEmpty { null }) }
                openaiTestResult = ConnectionTestResult.Idle
            },
            onTestDeepL = {
                scope.launch {
                    deeplTestResult = ConnectionTestResult.Testing
                    deeplTestResult = testDeepLConnection(
                        appModule.httpClient,
                        serviceConfig.deeplApiKey ?: ""
                    )
                }
            },
            onTestOpenAI = {
                scope.launch {
                    openaiTestResult = ConnectionTestResult.Testing
                    openaiTestResult = testOpenAIConnection(
                        appModule.httpClient,
                        serviceConfig.openaiApiKey ?: ""
                    )
                }
            }
        )
    }
}

// ========== Active Service Section ==========

@Composable
private fun ActiveServiceSection(
    i18n: I18nManager,
    selectedService: TranslationServiceOption,
    onServiceSelected: (TranslationServiceOption) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsSectionCard(
        title = i18n["settings.translation.activeService"],
        description = i18n["settings.translation.activeService.description"],
        modifier = modifier
    ) {
        ServiceDropdown(
            selectedService = selectedService,
            onServiceSelected = onServiceSelected,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ServiceDropdown(
    selectedService: TranslationServiceOption,
    onServiceSelected: (TranslationServiceOption) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = "${selectedService.displayName} ${if (!selectedService.requiresApiKey) "(Free)" else ""}",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            leadingIcon = {
                Icon(
                    imageVector = selectedService.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            TranslationServiceOption.entries.forEach { service ->
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = service.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = service.displayName,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (!service.requiresApiKey) {
                                        Surface(
                                            color = AppColors.success.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "Free",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = AppColors.success,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = service.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {
                        onServiceSelected(service)
                        expanded = false
                    },
                    leadingIcon = if (service == selectedService) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                )
            }
        }
    }
}

// ========== LibreTranslate Settings ==========

@Composable
private fun LibreTranslateSettingsCard(
    i18n: I18nManager,
    instanceUrl: String,
    testResult: ConnectionTestResult,
    onInstanceUrlChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = i18n["settings.translation.libreTranslate.title"],
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
            }

            // Instance URL field
            OutlinedTextField(
                value = instanceUrl,
                onValueChange = onInstanceUrlChange,
                label = { Text(i18n["settings.translation.instanceUrl"]) },
                placeholder = { Text("https://libretranslate.com") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            // Test connection button and result
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppButton(
                    text = i18n["settings.translation.testConnection"],
                    onClick = onTestConnection,
                    style = ButtonStyle.Secondary,
                    size = ButtonSize.Small,
                    enabled = instanceUrl.isNotBlank() && testResult !is ConnectionTestResult.Testing,
                    loading = testResult is ConnectionTestResult.Testing
                )

                // Test result indicator
                ConnectionTestResultIndicator(result = testResult)
            }
        }
    }
}

// ========== Other Services Section ==========

@Composable
private fun OtherServicesSection(
    i18n: I18nManager,
    deeplApiKey: String,
    openaiApiKey: String,
    deeplTestResult: ConnectionTestResult,
    openaiTestResult: ConnectionTestResult,
    onDeeplApiKeyChange: (String) -> Unit,
    onOpenaiApiKeyChange: (String) -> Unit,
    onTestDeepL: () -> Unit,
    onTestOpenAI: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Section header
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = i18n["settings.translation.otherServices"],
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = i18n["settings.translation.otherServices.description"],
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // DeepL API Key
        ApiKeyField(
            label = i18n["settings.translation.deeplApiKey"],
            value = deeplApiKey,
            onValueChange = onDeeplApiKeyChange,
            testResult = deeplTestResult,
            onTest = onTestDeepL,
            helpUrl = "https://www.deepl.com/pro-api",
            helpText = i18n["settings.translation.howToGetApiKey"],
            placeholder = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx:fx"
        )

        // OpenAI API Key
        ApiKeyField(
            label = i18n["settings.translation.openaiApiKey"],
            value = openaiApiKey,
            onValueChange = onOpenaiApiKeyChange,
            testResult = openaiTestResult,
            onTest = onTestOpenAI,
            helpUrl = "https://platform.openai.com/api-keys",
            helpText = i18n["settings.translation.howToGetApiKey"],
            placeholder = "sk-..."
        )
    }
}

@Composable
private fun ApiKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    testResult: ConnectionTestResult,
    onTest: () -> Unit,
    helpUrl: String,
    helpText: String,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    var isPasswordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Label
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        // API Key input with visibility toggle
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Visibility toggle
                    IconButton(
                        onClick = { isPasswordVisible = !isPasswordVisible },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (isPasswordVisible) "Hide" else "Show",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            visualTransformation = if (isPasswordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        // Test button and result
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Help link
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { uriHandler.openUri(helpUrl) }
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                @Suppress("DEPRECATION")
                Icon(
                    imageVector = Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = helpText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp)
                )
            }

            // Test button and result
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConnectionTestResultIndicator(result = testResult)

                AppButton(
                    text = "Test",
                    onClick = onTest,
                    style = ButtonStyle.Text,
                    size = ButtonSize.Small,
                    enabled = value.isNotBlank() && testResult !is ConnectionTestResult.Testing,
                    loading = testResult is ConnectionTestResult.Testing
                )
            }
        }
    }
}

// ========== Connection Test Result Indicator ==========

@Composable
private fun ConnectionTestResultIndicator(
    result: ConnectionTestResult,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = result !is ConnectionTestResult.Idle && result !is ConnectionTestResult.Testing,
        enter = fadeIn() + expandHorizontally(),
        exit = fadeOut() + shrinkHorizontally()
    ) {
        when (result) {
            is ConnectionTestResult.Success -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = AppColors.success,
                        shape = CircleShape,
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.success
                    )
                }
            }
            is ConnectionTestResult.Error -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = AppColors.error,
                        shape = CircleShape,
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.error
                    )
                }
            }
            else -> {}
        }
    }
}

// ========== Connection Testing Functions ==========

/**
 * Tests connection to a LibreTranslate instance.
 */
private suspend fun testLibreTranslateConnection(
    httpClient: HttpClient,
    instanceUrl: String
): ConnectionTestResult {
    if (instanceUrl.isBlank()) {
        return ConnectionTestResult.Error("URL is empty")
    }

    return try {
        val url = instanceUrl.trimEnd('/')
        val response = httpClient.get("$url/languages")

        when (response.status) {
            HttpStatusCode.OK -> {
                ConnectionTestResult.Success("Connected")
            }
            else -> {
                ConnectionTestResult.Error("HTTP ${response.status.value}")
            }
        }
    } catch (e: Exception) {
        ConnectionTestResult.Error(e.message?.take(30) ?: "Connection failed")
    }
}

/**
 * Tests DeepL API key validity.
 */
private suspend fun testDeepLConnection(
    httpClient: HttpClient,
    apiKey: String
): ConnectionTestResult {
    if (apiKey.isBlank()) {
        return ConnectionTestResult.Error("API key is empty")
    }

    return try {
        val baseUrl = if (apiKey.endsWith(":fx")) {
            "https://api-free.deepl.com/v2"
        } else {
            "https://api.deepl.com/v2"
        }

        val response = httpClient.get("$baseUrl/usage") {
            header("Authorization", "DeepL-Auth-Key $apiKey")
        }

        when (response.status) {
            HttpStatusCode.OK -> {
                ConnectionTestResult.Success("Valid")
            }
            HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized -> {
                ConnectionTestResult.Error("Invalid API key")
            }
            else -> {
                ConnectionTestResult.Error("HTTP ${response.status.value}")
            }
        }
    } catch (e: Exception) {
        ConnectionTestResult.Error(e.message?.take(30) ?: "Connection failed")
    }
}

/**
 * Tests OpenAI API key validity.
 */
private suspend fun testOpenAIConnection(
    httpClient: HttpClient,
    apiKey: String
): ConnectionTestResult {
    if (apiKey.isBlank()) {
        return ConnectionTestResult.Error("API key is empty")
    }

    return try {
        val response = httpClient.get("https://api.openai.com/v1/models") {
            header("Authorization", "Bearer $apiKey")
        }

        when (response.status) {
            HttpStatusCode.OK -> {
                ConnectionTestResult.Success("Valid")
            }
            HttpStatusCode.Unauthorized -> {
                ConnectionTestResult.Error("Invalid API key")
            }
            else -> {
                ConnectionTestResult.Error("HTTP ${response.status.value}")
            }
        }
    } catch (e: Exception) {
        ConnectionTestResult.Error(e.message?.take(30) ?: "Connection failed")
    }
}
