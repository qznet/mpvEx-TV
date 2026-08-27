package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.preferences.CustomButton
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.theme.controlColor
import app.marlboroadvance.mpvex.ui.theme.spacing
import org.koin.compose.koinInject

/**
 * Renders the enabled, order-preserving custom mpv-command buttons as a centered pill row
 * on the player overlay. Each pill shows the button [CustomButton.label] and runs
 * [CustomButton.execute] on click.
 *
 * Placement/sizing notes:
 * - Pill width follows the label (min ~3 CJK chars) so "三个汉字" sized buttons emerge naturally.
 * - Click also resets the controls auto-hide timer via [LocalPlayerButtonsClickEvent].
 */
@Composable
fun CustomButtonsRow(
  modifier: Modifier = Modifier,
  hideBackground: Boolean,
) {
  val playerPreferences = koinInject<PlayerPreferences>()
  val buttons by playerPreferences.customButtons.collectAsState()
  val enabledButtons = buttons.filter { it.enabled }
  if (enabledButtons.isEmpty()) return

  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(spacing.small),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    enabledButtons.forEach { button ->
      CustomButtonPill(
        button = button,
        hideBackground = hideBackground,
      )
    }
  }
}

@Composable
private fun CustomButtonPill(
  button: CustomButton,
  hideBackground: Boolean,
) {
  val clickEvent = LocalPlayerButtonsClickEvent.current
  val haptic = LocalHapticFeedback.current

  Surface(
    shape = RoundedCornerShape(50),
    color =
      if (hideBackground) {
        Color.Transparent
      } else {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
      },
    contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
    border =
      if (hideBackground) {
        null
      } else {
        BorderStroke(
          1.dp,
          MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
      },
    modifier =
      Modifier
        .defaultMinSize(minWidth = 56.dp)
        .clickable {
          clickEvent()
          haptic.performHapticFeedback(HapticFeedbackType.LongPress)
          button.execute()
        },
  ) {
    Text(
      text = button.label,
      textAlign = TextAlign.Center,
      fontSize = 16.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier =
        Modifier
          .padding(horizontal = 16.dp, vertical = 10.dp),
    )
  }
}
