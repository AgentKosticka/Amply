package com.agentkosticka.amply.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agentkosticka.amply.R
import com.agentkosticka.amply.shizuku.ShizukuPermissionState
import com.agentkosticka.amply.ui.theme.NothingColors
import kotlinx.coroutines.launch

/**
 * Main Setup Wizard Screen with 5 slides
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SetupWizardScreen(
    viewModel: SetupViewModel,
    onSetupComplete: () -> Unit,
    onRequestOptionalPermissions: () -> Unit = {}
) {
    val pagerState = rememberPagerState(pageCount = { 5 })
    val currentPage by viewModel.currentPage.collectAsState()
    val permissionState by viewModel.permissionState.collectAsState()
    val scope = rememberCoroutineScope()

    // Sync pager with view model
    LaunchedEffect(currentPage) {
        if (pagerState.currentPage != currentPage) {
            pagerState.animateScrollToPage(currentPage)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        viewModel.goToPage(pagerState.currentPage)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(NothingColors.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> WelcomeSlide(
                    onNext = { scope.launch { pagerState.animateScrollToPage(1) } }
                )
                1 -> ExplanationSlide(
                    onNext = { scope.launch { pagerState.animateScrollToPage(2) } }
                )
                2 -> ShizukuCheckSlide(
                    permissionState = permissionState,
                    onInstallShizuku = { viewModel.openShizukuDownload() },
                    onHaveIt = {
                        viewModel.checkShizukuState()
                        scope.launch { pagerState.animateScrollToPage(3) }
                    },
                    onStartShizuku = { viewModel.openShizukuApp() }
                )
                3 -> PermissionSlide(
                    permissionState = permissionState,
                    onGrantPermission = { viewModel.requestShizukuPermission() },
                    onNext = { scope.launch { pagerState.animateScrollToPage(4) } },
                    onRetry = { viewModel.checkShizukuState() }
                )
                4 -> SuccessSlide(
                    onFinish = {
                        onRequestOptionalPermissions()
                        viewModel.completeSetup()
                        onSetupComplete()
                    }
                )
            }
        }

        // Page indicators
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(5) { index ->
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (index == currentPage) NothingColors.White else NothingColors.GreyDim,
                            shape = RoundedCornerShape(4.dp)
                        )
                )
            }
        }
    }
}

/**
 * Slide 1: Welcome - Premium Nothing-style design
 */
@Composable
fun WelcomeSlide(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App Title with red accent
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "AMPLY",
                style = MaterialTheme.typography.displayLarge,
                color = NothingColors.White
            )
            Text(
                text = "(1)",
                style = MaterialTheme.typography.displayMedium,
                color = NothingColors.Red
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        Image(
            painter = painterResource(id = R.drawable.ic_amply_logo),
            contentDescription = "Amply logo",
            modifier = Modifier
                .size(140.dp)
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "TAKE CONTROL OF",
            style = MaterialTheme.typography.titleLarge,
            color = NothingColors.GreyMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = "YOUR AUDIO MATRIX",
            style = MaterialTheme.typography.headlineMedium,
            color = NothingColors.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(80.dp))

        NothingButton(
            text = "BEGIN",
            onClick = onNext,
            accent = true
        )
    }
}

/**
 * Slide 2: Explanation
 */
@Composable
fun ExplanationSlide(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "WHY SHIZUKU?",
            style = MaterialTheme.typography.displayMedium,
            color = NothingColors.White
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "To intercept volume keys and control per-app audio, we need system-level access.\n\nShizuku provides this without root.",
            style = MaterialTheme.typography.bodyLarge,
            color = NothingColors.GreyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(96.dp))

        NothingButton(
            text = "NEXT",
            onClick = onNext
        )
    }
}

/**
 * Slide 3: Shizuku Check
 */
@Composable
fun ShizukuCheckSlide(
    permissionState: ShizukuPermissionState,
    onInstallShizuku: () -> Unit,
    onHaveIt: () -> Unit,
    onStartShizuku: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "DO YOU HAVE SHIZUKU?",
            style = MaterialTheme.typography.displayMedium,
            color = NothingColors.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(64.dp))

        when (permissionState) {
            ShizukuPermissionState.SHIZUKU_NOT_INSTALLED -> {
                NothingButton(
                    text = "INSTALL SHIZUKU",
                    onClick = onInstallShizuku
                )
            }
            ShizukuPermissionState.SHIZUKU_NOT_RUNNING -> {
                NothingButton(
                    text = "START SHIZUKU",
                    onClick = onStartShizuku
                )
                Spacer(modifier = Modifier.height(16.dp))
                NothingButton(
                    text = "I STARTED IT",
                    onClick = onHaveIt,
                    outlined = true
                )
            }
            else -> {
                NothingButton(
                    text = "I HAVE IT",
                    onClick = onHaveIt
                )
                Spacer(modifier = Modifier.height(16.dp))
                NothingButton(
                    text = "INSTALL SHIZUKU",
                    onClick = onInstallShizuku,
                    outlined = true
                )
            }
        }
    }
}

/**
 * Slide 4: Grant Permission
 */
@Composable
fun PermissionSlide(
    permissionState: ShizukuPermissionState,
    onGrantPermission: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "GRANT ACCESS",
            style = MaterialTheme.typography.displayMedium,
            color = NothingColors.White
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Tap below to grant Amply (1) access to Shizuku.",
            style = MaterialTheme.typography.bodyLarge,
            color = NothingColors.GreyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(64.dp))

        when (permissionState) {
            ShizukuPermissionState.GRANTED -> {
                Text(
                    text = "Status: ✓ Granted",
                    style = MaterialTheme.typography.labelLarge,
                    color = NothingColors.White
                )
                Spacer(modifier = Modifier.height(32.dp))
                NothingButton(
                    text = "CONTINUE",
                    onClick = onNext
                )
            }
            ShizukuPermissionState.DENIED -> {
                Text(
                    text = "Status: ✗ Denied",
                    style = MaterialTheme.typography.labelLarge,
                    color = NothingColors.Red
                )
                Spacer(modifier = Modifier.height(32.dp))
                NothingButton(
                    text = "RETRY",
                    onClick = onGrantPermission
                )
            }
            ShizukuPermissionState.SHIZUKU_NOT_RUNNING -> {
                Text(
                    text = "Status: ⚠ Shizuku Not Running",
                    style = MaterialTheme.typography.labelLarge,
                    color = NothingColors.Red
                )
                Spacer(modifier = Modifier.height(32.dp))
                NothingButton(
                    text = "RETRY",
                    onClick = onRetry
                )
            }
            else -> {
                Text(
                    text = "Status: ⚠ Pending",
                    style = MaterialTheme.typography.labelLarge,
                    color = NothingColors.GreyMedium
                )
                Spacer(modifier = Modifier.height(32.dp))
                NothingButton(
                    text = "GRANT PERMISSION",
                    onClick = onGrantPermission
                )
            }
        }
    }
}

/**
 * Slide 5: Success - Premium celebration design
 */
@Composable
fun SuccessSlide(onFinish: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Success checkmark
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(NothingColors.Red, RoundedCornerShape(50.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.displayLarge,
                color = NothingColors.White
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = "CONNECTED",
            style = MaterialTheme.typography.displayMedium,
            color = NothingColors.White
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Amply (1) is now ready to\nhijack your volume controls.\n\nAndroid may next ask for call state and notification access. Call state only distinguishes incoming from outgoing calls; Amply does not read call history or phone numbers.",
            style = MaterialTheme.typography.bodyLarge,
            color = NothingColors.GreyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(80.dp))

        NothingButton(
            text = "FINISH SETUP",
            onClick = onFinish,
            accent = true
        )
    }
}

/**
 * Nothing OS styled button component
 * Enhanced with red accent option
 */
@Composable
fun NothingButton(
    text: String,
    onClick: () -> Unit,
    outlined: Boolean = false,
    accent: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(32.dp),
        colors = when {
            accent -> ButtonDefaults.buttonColors(
                containerColor = NothingColors.Red,
                contentColor = NothingColors.White
            )
            outlined -> ButtonDefaults.outlinedButtonColors(
                containerColor = NothingColors.Black,
                contentColor = NothingColors.White
            )
            else -> ButtonDefaults.buttonColors(
                containerColor = NothingColors.White,
                contentColor = NothingColors.Black
            )
        },
        border = if (outlined) BorderStroke(1.dp, NothingColors.GreyDim) else null
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge
        )
    }
}
