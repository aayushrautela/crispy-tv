package com.crispy.tv.tv.ui.screens.auth

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.crispy.tv.tv.session.DeviceLoginState
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
fun SignInScreen(
    configError: Boolean,
    inFlight: Boolean,
    error: String?,
    onSignIn: (email: String, password: String) -> Unit,
    deviceLogin: DeviceLoginState,
    deviceLoginAvailable: Boolean,
    onStartDeviceLogin: () -> Unit,
    onCancelDeviceLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEmail by remember { mutableStateOf(configError || !deviceLoginAvailable) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.width(520.dp),
        ) {
            if (showEmail) {
                EmailSignInPanel(
                    configError = configError,
                    inFlight = inFlight,
                    error = error,
                    onSignIn = onSignIn,
                    onUseQr = {
                        onCancelDeviceLogin()
                        showEmail = false
                        onStartDeviceLogin()
                    },
                    qrAvailable = deviceLoginAvailable,
                )
            } else {
                QrSignInPanel(
                    deviceLogin = deviceLogin,
                    onRetry = onStartDeviceLogin,
                    onUseEmail = {
                        onCancelDeviceLogin()
                        showEmail = true
                    },
                )
            }
        }
    }
}

@Composable
private fun EmailSignInPanel(
    configError: Boolean,
    inFlight: Boolean,
    error: String?,
    onSignIn: (email: String, password: String) -> Unit,
    onUseQr: () -> Unit,
    qrAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Crispy",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (configError) {
                "Server configuration missing. Check SUPABASE_URL / backend settings."
            } else {
                "Sign in to continue"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(32.dp))
        AuthTextField(
            value = email,
            onValueChange = { email = it },
            hint = "Email",
            enabled = !configError && !inFlight,
        )
        Spacer(Modifier.height(16.dp))
        AuthTextField(
            value = password,
            onValueChange = { password = it },
            hint = "Password",
            enabled = !configError && !inFlight,
            isPassword = true,
        )
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(28.dp))
        SubmitButton(
            label = if (inFlight) "Signing in…" else "Sign in",
            enabled = !configError && !inFlight && email.isNotBlank() && password.isNotBlank(),
            onClick = { onSignIn(email, password) },
        )
        if (qrAvailable) {
            Spacer(Modifier.height(16.dp))
            SecondaryLink(
                label = "Sign in with a QR code instead",
                onClick = onUseQr,
            )
        }
    }
}

@Composable
private fun QrSignInPanel(
    deviceLogin: DeviceLoginState,
    onRetry: () -> Unit,
    onUseEmail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Crispy",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))

        when (deviceLogin) {
            is DeviceLoginState.AwaitingApproval -> {
                Text(
                    text = "Scan with your phone camera, or open the link below and enter the code.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .padding(12.dp),
                ) {
                    QrCodeImage(
                        content = deviceLogin.verificationUriComplete,
                        size = 216.dp,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = deviceLogin.verificationUri,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Enter code",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = deviceLogin.userCode,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    letterSpacing = 4.sp,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Waiting for approval…",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            is DeviceLoginState.Requesting, is DeviceLoginState.Idle -> {
                Text(
                    text = "Preparing sign-in…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is DeviceLoginState.Denied -> {
                QrFailureContent(message = "Sign-in was denied on your phone.", onRetry = onRetry)
            }
            is DeviceLoginState.Expired -> {
                QrFailureContent(message = "The code expired. Start again to get a new one.", onRetry = onRetry)
            }
            is DeviceLoginState.Failed -> {
                QrFailureContent(message = deviceLogin.message, onRetry = onRetry)
            }
        }

        Spacer(Modifier.height(24.dp))
        SecondaryLink(
            label = "Use email & password instead",
            onClick = onUseEmail,
        )
    }
}

@Composable
private fun QrFailureContent(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        SubmitButton(
            label = "Try again",
            enabled = true,
            onClick = onRetry,
            modifier = Modifier.width(280.dp),
        )
    }
}

@Composable
private fun QrCodeImage(content: String, size: Dp, modifier: Modifier = Modifier) {
    val sizePx = with(LocalDensity.current) { size.toPx() }.toInt().coerceAtLeast(1)
    val bitmap = remember(content, sizePx) {
        runCatching { generateQrBitmap(content, sizePx) }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Sign-in QR code",
            modifier = modifier.size(size),
        )
    }
}

private fun generateQrBitmap(content: String, sizePx: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        for (x in 0 until sizePx) {
            pixels[y * sizePx + x] = if (matrix.get(x, y)) black else white
        }
    }
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    return bitmap
}

@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    enabled: Boolean,
    isPassword: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onBackground,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Email,
        ),
        visualTransformation = if (isPassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.borderVariant),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun SubmitButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Text(text = label, color = contentColor, fontSize = 16.sp)
    }
}

@Composable
private fun SecondaryLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
    }
}
