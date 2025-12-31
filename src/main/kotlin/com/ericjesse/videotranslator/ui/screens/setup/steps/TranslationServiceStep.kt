@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.setup.steps

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.infrastructure.config.TranslationServiceConfig
import com.ericjesse.videotranslator.ui.components.*
import com.ericjesse.videotranslator.ui.components.CardElevation as AppCardElevation
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.theme.AppColors
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Translation service identifiers.
 */
enum class TranslationServiceType(val id: String) {
    LIBRE_TRANSLATE("libretranslate"),
    DEEPL("deepl"),
    OPENAI("openai");

    companion object {
        fun fromId(id: String): TranslationServiceType =
            entries.find { it.id == id } ?: LIBRE_TRANSLATE
    }
}


/**
 * Connection test result.
 */
sealed class ConnectionTestResult {
    data object NotTested : ConnectionTestResult()
    data object Testing : ConnectionTestResult()
    data object Success : ConnectionTestResult()
    data class Error(val message: String) : ConnectionTestResult()
}

/**
 * Translation service step of the setup wizard.
 *
 * Displays:
 * - Radio button list of services (LibreTranslate, DeepL, OpenAI)
 * - Service-specific configuration panels
 * - Test Connection button with result indicator
 * - Continue button (validates before proceeding)
 *
 * @param appModule Application module for accessing services.
 * @param selectedService The currently selected service ID.
 * @param onServiceSelected Callback when a service is selected.
 * @param onNext Callback when the user clicks Continue.
 * @param modifier Modifier to be applied to the step.
 */
@Composable
fun TranslationServiceStep(
    appModule: AppModule,
    selectedService: String,
    onServiceSelected: (String) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val i18n = appModule.i18nManager
    val configManager = appModule.configManager
    val httpClient = appModule.httpClient
    val libreTranslateService = appModule.libreTranslateService
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val uriHandler = LocalUriHandler.current

    // Load existing configuration
    val existingConfig = remember { configManager.getTranslationServiceConfig() }

    // Service configuration state
    var deeplApiKey by remember { mutableStateOf(existingConfig.deeplApiKey ?: "") }
    var openaiApiKey by remember { mutableStateOf(existingConfig.openaiApiKey ?: "") }

    // Password visibility
    var showDeeplKey by remember { mutableStateOf(false) }
    var showOpenaiKey by remember { mutableStateOf(false) }

    // Connection test state
    var connectionTestResult by remember { mutableStateOf<ConnectionTestResult>(ConnectionTestResult.NotTested) }

    // Validation state
    var validationError by remember { mutableStateOf<String?>(null) }

    // Animation
    var showContent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100)
        showContent = true
    }

    val contentAlpha by animateFloatAsState(
        targetValue = if (showContent) 1f else 0f,
        animationSpec = tween(400),
        label = "contentAlpha"
    )

    // Reset connection test when service changes
    LaunchedEffect(selectedService) {
        connectionTestResult = ConnectionTestResult.NotTested
        validationError = null
    }

    // Test connection function
    fun testConnection() {
        connectionTestResult = ConnectionTestResult.Testing
        scope.launch {
            try {
                when (TranslationServiceType.fromId(selectedService)) {
                    TranslationServiceType.LIBRE_TRANSLATE -> {
                        val serverUrl = libreTranslateService.serverUrl
                        val response = httpClient.get("$serverUrl/languages")
                        if (response.status.isSuccess()) {
                            connectionTestResult = ConnectionTestResult.Success
                        } else {
                            connectionTestResult = ConnectionTestResult.Error("HTTP ${response.status.value}")
                        }
                    }

                    TranslationServiceType.DEEPL -> {
                        if (deeplApiKey.isBlank()) {
                            connectionTestResult = ConnectionTestResult.Error("API key is required")
                            return@launch
                        }
                        // DeepL API test - check usage endpoint
                        val baseUrl = if (deeplApiKey.endsWith(":fx")) {
                            "https://api-free.deepl.com/v2"
                        } else {
                            "https://api.deepl.com/v2"
                        }
                        val response = httpClient.get("$baseUrl/usage") {
                            header("Authorization", "DeepL-Auth-Key $deeplApiKey")
                        }
                        if (response.status.isSuccess()) {
                            connectionTestResult = ConnectionTestResult.Success
                        } else if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.Unauthorized) {
                            connectionTestResult = ConnectionTestResult.Error("Invalid API key")
                        } else {
                            connectionTestResult = ConnectionTestResult.Error("HTTP ${response.status.value}")
                        }
                    }

                    TranslationServiceType.OPENAI -> {
                        if (openaiApiKey.isBlank()) {
                            connectionTestResult = ConnectionTestResult.Error("API key is required")
                            return@launch
                        }
                        // OpenAI API test - check models endpoint
                        val response = httpClient.get("https://api.openai.com/v1/models") {
                            header("Authorization", "Bearer $openaiApiKey")
                        }
                        if (response.status.isSuccess()) {
                            connectionTestResult = ConnectionTestResult.Success
                        } else if (response.status == HttpStatusCode.Unauthorized) {
                            connectionTestResult = ConnectionTestResult.Error("Invalid API key")
                        } else {
                            connectionTestResult = ConnectionTestResult.Error("HTTP ${response.status.value}")
                        }
                    }
                }
            } catch (e: Exception) {
                connectionTestResult = ConnectionTestResult.Error(e.message ?: "Connection failed")
            }
        }
    }

    // Validate and proceed
    fun validateAndProceed() {
        validationError = null

        when (TranslationServiceType.fromId(selectedService)) {
            TranslationServiceType.LIBRE_TRANSLATE -> {
                // Local server - no validation needed
            }

            TranslationServiceType.DEEPL -> {
                if (deeplApiKey.isBlank()) {
                    validationError = "Please enter a DeepL API key"
                    return
                }
            }

            TranslationServiceType.OPENAI -> {
                if (openaiApiKey.isBlank()) {
                    validationError = "Please enter an OpenAI API key"
                    return
                }
            }
        }

        // Save configuration
        val config = TranslationServiceConfig(
            deeplApiKey = deeplApiKey.takeIf { it.isNotBlank() },
            openaiApiKey = openaiApiKey.takeIf { it.isNotBlank() }
        )
        configManager.saveTranslationServiceConfig(config)

        onNext()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(contentAlpha)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp)
                .padding(end = 12.dp) // Extra padding for scrollbar
        ) {
            // Description
            Text(
                text = i18n["setup.translation.description"],
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Service options card
        AppCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = AppCardElevation.Low
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // LibreTranslate (Local)
                ServiceOptionWithConfig(
                    name = i18n["service.libretranslate.name"],
                    description = i18n["service.libretranslate.description"],
                    isSelected = selectedService == TranslationServiceType.LIBRE_TRANSLATE.id,
                    isRecommended = true,
                    recommendedText = i18n["setup.translation.recommended"],
                    onSelect = { onServiceSelected(TranslationServiceType.LIBRE_TRANSLATE.id) }
                ) {
                    // LibreTranslate configuration - just test connection for local server
                    ConnectionTestRow(
                        result = if (selectedService == TranslationServiceType.LIBRE_TRANSLATE.id)
                            connectionTestResult else ConnectionTestResult.NotTested,
                        onTestConnection = { testConnection() }
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // DeepL
                ServiceOptionWithConfig(
                    name = i18n["service.deepl.name"],
                    description = i18n["service.deepl.description"],
                    isSelected = selectedService == TranslationServiceType.DEEPL.id,
                    onSelect = { onServiceSelected(TranslationServiceType.DEEPL.id) }
                ) {
                    // DeepL configuration
                    ApiKeyConfig(
                        i18n = i18n,
                        apiKey = deeplApiKey,
                        onApiKeyChanged = { deeplApiKey = it },
                        showKey = showDeeplKey,
                        onToggleVisibility = { showDeeplKey = !showDeeplKey },
                        howToGetUrl = "https://www.deepl.com/pro-api",
                        connectionTestResult = if (selectedService == TranslationServiceType.DEEPL.id)
                            connectionTestResult else ConnectionTestResult.NotTested,
                        onTestConnection = { testConnection() }
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // OpenAI
                ServiceOptionWithConfig(
                    name = i18n["service.openai.name"],
                    description = i18n["service.openai.description"],
                    isSelected = selectedService == TranslationServiceType.OPENAI.id,
                    onSelect = { onServiceSelected(TranslationServiceType.OPENAI.id) }
                ) {
                    // OpenAI configuration
                    ApiKeyConfig(
                        i18n = i18n,
                        apiKey = openaiApiKey,
                        onApiKeyChanged = { openaiApiKey = it },
                        showKey = showOpenaiKey,
                        onToggleVisibility = { showOpenaiKey = !showOpenaiKey },
                        howToGetUrl = "https://platform.openai.com/api-keys",
                        connectionTestResult = if (selectedService == TranslationServiceType.OPENAI.id)
                            connectionTestResult else ConnectionTestResult.NotTested,
                        onTestConnection = { testConnection() }
                    )
                }
            }
        }

        // Validation error
        if (validationError != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = validationError!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Continue button
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            AppButton(
                text = i18n["action.continue"],
                onClick = { validateAndProceed() },
                style = ButtonStyle.Primary,
                size = ButtonSize.Large
            )
        }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Scrollbar
        VerticalScrollbar(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
            adapter = rememberScrollbarAdapter(scrollState)
        )
    }
}

@Composable
private fun ServiceOptionWithConfig(
    name: String,
    description: String,
    isSelected: Boolean,
    isRecommended: Boolean = false,
    recommendedText: String = "Recommended",
    onSelect: () -> Unit,
    configContent: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onSelect)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )

                    if (isRecommended) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = recommendedText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Config panel (animated)
        AnimatedVisibility(
            visible = isSelected,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, top = 8.dp, bottom = 8.dp)
            ) {
                configContent()
            }
        }
    }
}

@Composable
private fun ApiKeyConfig(
    i18n: I18nManager,
    apiKey: String,
    onApiKeyChanged: (String) -> Unit,
    showKey: Boolean,
    onToggleVisibility: () -> Unit,
    howToGetUrl: String,
    connectionTestResult: ConnectionTestResult,
    onTestConnection: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // API key field
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChanged,
            label = { Text(i18n["apikey.label"]) },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = onToggleVisibility) {
                    Icon(
                        imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showKey) i18n["action.hide"] else i18n["action.show"]
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            textStyle = MaterialTheme.typography.bodySmall
        )

        // How to get API key link
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { uriHandler.openUri(howToGetUrl) }
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = i18n["apikey.howToGet"],
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Test connection
        ConnectionTestRow(
            result = connectionTestResult,
            onTestConnection = onTestConnection
        )
    }
}

@Composable
private fun ConnectionTestRow(
    result: ConnectionTestResult,
    onTestConnection: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppButton(
            text = "Test Connection",
            onClick = onTestConnection,
            style = ButtonStyle.Secondary,
            size = ButtonSize.Small,
            enabled = result !is ConnectionTestResult.Testing,
            loading = result is ConnectionTestResult.Testing
        )

        // Result indicator
        when (result) {
            is ConnectionTestResult.NotTested -> {}

            is ConnectionTestResult.Testing -> {
                Text(
                    text = "Testing...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is ConnectionTestResult.Success -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = AppColors.success,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.success
                    )
                }
            }

            is ConnectionTestResult.Error -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
