package com.rm.mp3tomidi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.mp3tomidi.R
import com.rm.mp3tomidi.ui.theme.BrandPink
import com.rm.mp3tomidi.ui.theme.BrandTeal
import com.rm.mp3tomidi.ui.theme.BrandYellow

// Teal and pink are near-complementary, so a plain 2-stop gradient between them interpolates
// through a muddy gray midpoint in RGB space -- routing through yellow (which is also literally
// the app icon's 3-color order, top to bottom) keeps it vivid the whole way across instead.
val HeaderGradient = Brush.horizontalGradient(listOf(BrandTeal, BrandYellow, BrandPink))

/**
 * The gradient title header, shared by both top-level screens (see [AppScreen]). [switchIcon] is
 * a single small glyph shown in a circular button anchored to the trailing edge -- same pattern
 * as roge-rm/ScaleInKey's HeroBand.kt -- so switching between the Convert and Play screens
 * reads consistently with that sibling app.
 */
@Composable
fun AppHeader(onSwitchScreen: () -> Unit, switchIcon: String, switchContentDescription: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
            .background(HeaderGradient)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
        )
        Surface(
            onClick = onSwitchScreen,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(32.dp)
                .semantics { contentDescription = switchContentDescription },
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.18f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(switchIcon, fontSize = 14.sp, color = Color.White)
            }
        }
    }
}
