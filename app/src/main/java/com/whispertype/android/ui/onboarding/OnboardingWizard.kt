package com.whispertype.android.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.whispertype.android.R
import com.whispertype.android.data.secrets.KeyProvider
import com.whispertype.android.ui.theme.AppLogo
import com.whispertype.android.ui.theme.StudioCard
import com.whispertype.android.ui.theme.StudioColors
import com.whispertype.android.ui.theme.StudioCta
import com.whispertype.android.ui.theme.StudioGhostCta
import com.whispertype.android.ui.theme.StudioType
import com.whispertype.android.ui.waveform.RealTimeWaveform
import kotlinx.coroutines.launch

private enum class OnboardingStep {
    Welcome,
    HowItWorks,
    Overlay,
    Mic,
    Accessibility,
    Restricted,
    GeminiKey,
    Ready,
}

@Composable
fun OnboardingWizard(
    overlayGranted: Boolean,
    keyProvider: KeyProvider,
    hasMic: () -> Boolean,
    hasAccessibility: () -> Boolean,
    onRequestOverlay: () -> Unit,
    onRequestMicNotifications: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onContinue: () -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val accessibilityOn = remember(refresh) { hasAccessibility() }
    val micGranted = remember(refresh) { hasMic() }
    var attemptedAccessibility by remember { mutableStateOf(false) }
    var overlayRequested by remember { mutableStateOf(false) }
    var micRequested by remember { mutableStateOf(false) }
    var appInfoOpened by remember { mutableStateOf(false) }
    var sandboxText by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(OnboardingStep.Welcome) }
    LaunchedEffect(overlayGranted, refresh, step) {
        if (step == OnboardingStep.Overlay && overlayGranted && overlayRequested) {
            overlayRequested = false
            step = OnboardingStep.Mic
        }
    }
    LaunchedEffect(micGranted, refresh, step) {
        if (step == OnboardingStep.Mic && micGranted && micRequested) {
            micRequested = false
            step = OnboardingStep.Accessibility
        }
    }
    LaunchedEffect(accessibilityOn, refresh, step) {
        if (!accessibilityOn && attemptedAccessibility && step == OnboardingStep.Accessibility) {
            step = OnboardingStep.Restricted
        }
        if (accessibilityOn && (step == OnboardingStep.Accessibility || step == OnboardingStep.Restricted)) {
            attemptedAccessibility = false
            appInfoOpened = false
            step = OnboardingStep.GeminiKey
        }
    }
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var keyFeedback by remember { mutableStateOf<String?>(null) }
    val keySet = remember(refresh) { keyProvider.hasKey() }
    val keySavedMessage = stringResource(R.string.settings_key_saved)
    val keySaveFailedMessage = stringResource(R.string.settings_key_save_failed)

    Surface(modifier = Modifier.fillMaxSize(), color = StudioColors.Background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            StepDots(current = step.chapterIndex(), total = 5)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    OnboardingStepContent(
                        step = step,
                        keySet = keySet,
                        keyInput = keyInput,
                        onKeyInputChange = { keyInput = it },
                        keyFeedback = keyFeedback,
                        sandboxText = sandboxText,
                        onSandboxTextChange = { sandboxText = it },
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OnboardingStepActions(
                    step = step,
                    overlayGranted = overlayGranted,
                    keySet = keySet,
                    keyInput = keyInput,
                    appInfoOpened = appInfoOpened,
                    onRequestOverlay = {
                        overlayRequested = true
                        onRequestOverlay()
                    },
                    onRequestMicNotifications = {
                        micRequested = true
                        onRequestMicNotifications()
                    },
                    onOpenAccessibility = {
                        attemptedAccessibility = true
                        onOpenAccessibility()
                    },
                    onOpenAppInfo = {
                        appInfoOpened = true
                        onOpenAppInfo()
                    },
                    onContinue = onContinue,
                    onSaveKey = {
                        scope.launch {
                            val saved = keyProvider.storeKey(keyInput.trim())
                            keyFeedback = if (saved) {
                                keyInput = ""
                                refresh++
                                step = OnboardingStep.Ready
                                keySavedMessage
                            } else {
                                keySaveFailedMessage
                            }
                        }
                    },
                    onAdvance = { step = it },
                )

                if (step != OnboardingStep.Welcome && step != OnboardingStep.Ready) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        androidx.compose.material3.TextButton(onClick = { step = step.previous() }) {
                            Text(stringResource(R.string.onboard_back), style = StudioType.rowDesc)
                        }
                        androidx.compose.material3.TextButton(onClick = { step = step.next() }) {
                            Text(stringResource(R.string.onboard_skip_for_now), style = StudioType.rowDesc)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingStepContent(
    step: OnboardingStep,
    keySet: Boolean,
    keyInput: String,
    onKeyInputChange: (String) -> Unit,
    keyFeedback: String?,
    sandboxText: String,
    onSandboxTextChange: (String) -> Unit,
) {
    when (step) {
        OnboardingStep.Welcome -> {
            AppLogo(size = 56.dp)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.onboard_welcome_title), style = StudioType.onboardBig)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.onboard_welcome_body), style = StudioType.why)
        }
        OnboardingStep.HowItWorks -> {
            Text(stringResource(R.string.onboard_how_title), style = StudioType.onboardBig)
            Spacer(Modifier.height(16.dp))
            StudioCard(radius = 16) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppLogo(size = 44.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.onboard_how_step1_title), style = StudioType.rowTitle)
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.onboard_how_step1_desc), style = StudioType.why)
                }
            }
            Spacer(Modifier.height(12.dp))
            StudioCard(radius = 16) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PreviewPill()
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(stringResource(R.string.onboard_how_step2_title), style = StudioType.rowTitle)
                        Text(stringResource(R.string.onboard_how_step2_desc), style = StudioType.why)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.onboard_how_step3_title), style = StudioType.rowTitle)
                        Text(stringResource(R.string.onboard_how_step3_desc), style = StudioType.why)
                    }
                }
            }
        }
        OnboardingStep.Overlay -> {
            Text(stringResource(R.string.onboard_overlay_title), style = StudioType.onboardBig)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.onboard_overlay_body), style = StudioType.why)
            Spacer(Modifier.height(12.dp))
            StudioCard(radius = 16) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.onboarding_overlay), style = StudioType.rowTitle)
                    Text(stringResource(R.string.onboard_overlay_required), style = StudioType.rowDesc)
                }
            }
        }
        OnboardingStep.Mic -> {
            Text(stringResource(R.string.onboard_mic_title), style = StudioType.onboardBig)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.onboard_mic_body), style = StudioType.why)
        }
        OnboardingStep.Accessibility -> {
            Text(stringResource(R.string.onboard_a11y_title), style = StudioType.onboardBig)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.onboard_a11y_subtitle), style = StudioType.why)
            Spacer(Modifier.height(14.dp))
            StudioCard(radius = 16) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InstructionStepRow("1", stringResource(R.string.onboard_a11y_step1))
                    InstructionStepRow("2", stringResource(R.string.onboard_a11y_step2))
                    InstructionStepRow("3", stringResource(R.string.onboard_a11y_step3))
                    InstructionStepRow("4", stringResource(R.string.onboard_a11y_step4))
                }
            }
            Spacer(Modifier.height(12.dp))
            StudioCard(radius = 16) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = StudioColors.SurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = StudioColors.Accent,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Text(stringResource(R.string.onboard_a11y_privacy), style = StudioType.why)
                }
            }
        }
        OnboardingStep.Restricted -> {
            Text(stringResource(R.string.onboard_restricted_title), style = StudioType.onboardBig)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.onboard_restricted_subtitle), style = StudioType.why)
            Spacer(Modifier.height(14.dp))
            StudioCard(radius = 16) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InstructionStepRow("1", stringResource(R.string.onboard_restricted_step1))
                    InstructionStepRow("2", stringResource(R.string.onboard_restricted_step2))
                    InstructionStepRow("3", stringResource(R.string.onboard_restricted_step3))
                    InstructionStepRow("4", stringResource(R.string.onboard_restricted_step4))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.onboard_restricted_tip), style = StudioType.heroLabel)
        }
        OnboardingStep.GeminiKey -> {
            val context = LocalContext.current
            Text(stringResource(R.string.onboard_key_title), style = StudioType.onboardBig)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.onboard_key_subtitle), style = StudioType.why)
            Spacer(Modifier.height(14.dp))
            StudioCard(radius = 16) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.onboard_key_step1_title), style = StudioType.rowTitle)
                    Text(stringResource(R.string.onboard_key_step1_desc), style = StudioType.why)
                    StudioGhostCta(
                        text = stringResource(R.string.onboard_key_step1_button),
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, "https://aistudio.google.com/api-keys".toUri()),
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            StudioCard(radius = 16) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.onboard_key_step2_title), style = StudioType.rowTitle)
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = onKeyInputChange,
                        placeholder = { Text(stringResource(R.string.settings_key_hint), style = StudioType.rowDesc) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    keyFeedback?.let {
                        Text(it, style = StudioType.rowDesc.copy(color = StudioColors.Accent))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.onboard_key_privacy), style = StudioType.heroLabel)
        }
        OnboardingStep.Ready -> {
            Text(stringResource(R.string.onboard_ready_title), style = StudioType.greeting)
            Spacer(Modifier.height(16.dp))
            StudioCard(radius = 18) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.onboard_ready_card_title), style = StudioType.rowTitle)
                    Text(stringResource(R.string.onboard_ready_card_desc), style = StudioType.why)
                    OutlinedTextField(
                        value = sandboxText,
                        onValueChange = onSandboxTextChange,
                        placeholder = { Text(stringResource(R.string.onboard_ready_sandbox_hint), style = StudioType.rowDesc) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.onboard_ready_body), style = StudioType.why)
        }
    }
}

@Composable
private fun OnboardingStepActions(
    step: OnboardingStep,
    overlayGranted: Boolean,
    keySet: Boolean,
    keyInput: String,
    appInfoOpened: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestMicNotifications: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onContinue: () -> Unit,
    onSaveKey: () -> Unit,
    onAdvance: (OnboardingStep) -> Unit,
) {
    when (step) {
        OnboardingStep.Welcome -> StudioCta(stringResource(R.string.onboard_continue), onClick = { onAdvance(OnboardingStep.HowItWorks) })
        OnboardingStep.HowItWorks -> StudioCta(stringResource(R.string.onboard_continue), onClick = { onAdvance(OnboardingStep.Overlay) })
        OnboardingStep.Overlay -> StudioCta(stringResource(R.string.onboard_allow_overlay), onClick = onRequestOverlay)
        OnboardingStep.Mic -> StudioCta(stringResource(R.string.onboard_allow_mic), onClick = onRequestMicNotifications)
        OnboardingStep.Accessibility -> StudioCta(stringResource(R.string.onboard_open_accessibility), onClick = onOpenAccessibility)
        OnboardingStep.Restricted -> {
            if (appInfoOpened) {
                StudioCta(stringResource(R.string.onboard_open_accessibility), onClick = onOpenAccessibility)
                StudioGhostCta(stringResource(R.string.onboard_open_appinfo), onClick = onOpenAppInfo)
            } else {
                StudioCta(stringResource(R.string.onboard_open_appinfo), onClick = onOpenAppInfo)
                StudioGhostCta(stringResource(R.string.onboard_open_accessibility), onClick = onOpenAccessibility)
            }
        }
        OnboardingStep.GeminiKey -> {
            if (keyInput.isNotBlank()) {
                StudioCta(stringResource(R.string.onboard_key_save_continue), onClick = onSaveKey)
            } else if (keySet) {
                StudioCta(stringResource(R.string.onboard_continue), onClick = { onAdvance(OnboardingStep.Ready) })
            } else {
                StudioCta(stringResource(R.string.onboard_continue), onClick = { onAdvance(OnboardingStep.Ready) })
            }
        }
        OnboardingStep.Ready -> StudioCta(
            stringResource(R.string.onboarding_get_started),
            onClick = onContinue,
            enabled = overlayGranted,
        )
    }
}

@Composable
private fun InstructionStepRow(
    number: String,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = StudioColors.SurfaceVariant,
            modifier = Modifier.size(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number,
                    style = StudioType.rowTitle.copy(fontSize = 12.sp, color = StudioColors.Accent),
                )
            }
        }
        Text(text = text, style = StudioType.rowTitle.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PreviewPill() {
    Surface(
        shape = RoundedCornerShape(50),
        color = StudioColors.Surface.copy(alpha = 0.88f),
        modifier = Modifier.border(1.dp, StudioColors.Hairline, RoundedCornerShape(50)),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(shape = CircleShape, color = StudioColors.ErrorBanner, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = StudioColors.ErrorOnBanner, modifier = Modifier.size(18.dp))
                }
            }
            RealTimeWaveform(
                amplitude = 0.45f,
                modifier = Modifier.size(width = 96.dp, height = 44.dp),
                lineColor = StudioColors.Accent,
                isListening = true,
            )
            Surface(shape = CircleShape, color = StudioColors.OkBanner, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = StudioColors.Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(total) { i ->
            val active = i == current.coerceIn(0, total - 1)
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.5.dp)
                    .height(6.dp)
                    .width(if (active) 16.dp else 6.dp)
                    .background(
                        color = if (active) StudioColors.Accent else StudioColors.SurfaceVariant,
                        shape = RoundedCornerShape(50),
                    ),
            )
        }
    }
}

private fun OnboardingStep.chapterIndex(): Int = when (this) {
    OnboardingStep.Welcome, OnboardingStep.HowItWorks -> 0
    OnboardingStep.Overlay -> 1
    OnboardingStep.Mic -> 2
    OnboardingStep.Accessibility, OnboardingStep.Restricted -> 3
    OnboardingStep.GeminiKey, OnboardingStep.Ready -> 4
}

private fun OnboardingStep.previous(): OnboardingStep =
    OnboardingStep.entries[(ordinal - 1).coerceAtLeast(0)]

private fun OnboardingStep.next(): OnboardingStep =
    OnboardingStep.entries[(ordinal + 1).coerceAtMost(OnboardingStep.entries.lastIndex)]
