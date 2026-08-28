package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import kotlinx.coroutines.delay

/**
 * Settings dialog for the "skip intro / outro" feature, opened from the Custom Skip player
 * button. Laid out top-to-bottom so a TV remote can walk it with up/down and toggle/enter:
 *
 * 1. Enable checkbox
 * 2. Intro length in seconds
 * 3. Outro length in seconds
 *
 * Values are committed to [PlayerPreferences] as they change, so the running player picks
 * them up on the next file (and the outro check reads them live).
 */
@Composable
fun SkipIntroOutroSheet(
  playerPreferences: PlayerPreferences,
  onDismissRequest: () -> Unit,
) {
  val enabled by playerPreferences.skipIntroOutroEnabled.collectAsState()
  val introSeconds by playerPreferences.skipIntroSeconds.collectAsState()
  val outroSeconds by playerPreferences.skipOutroSeconds.collectAsState()

  // TV focus: the container itself must not take focus, so the request falls through to the
  // first focusable child (the enable row).
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) {
    delay(60)
    runCatching { focusRequester.requestFocus() }
  }

  Dialog(onDismissRequest = onDismissRequest) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 4.dp,
      modifier =
        Modifier
          .fillMaxWidth(0.92f)
          .focusRequester(focusRequester)
          .focusProperties { canFocus = false },
    ) {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = "跳过片头片尾",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
        Text(
          text = "开始播放时若处于片头范围内，则跳到片头结束处；播放超过 95% 后进入片尾范围，则自动播放下一集。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        // 1. Enable
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .clickable { playerPreferences.skipIntroOutroEnabled.set(!enabled) }
              .padding(vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Checkbox(checked = enabled, onCheckedChange = null)
          Spacer(Modifier.width(10.dp))
          Text(
            text = "启用",
            style = MaterialTheme.typography.titleMedium,
          )
        }

        // 2. Intro seconds
        SecondsRow(
          label = "片头秒数",
          value = introSeconds,
          onValueChange = { playerPreferences.skipIntroSeconds.set(it) },
        )

        // 3. Outro seconds
        SecondsRow(
          label = "片尾秒数",
          value = outroSeconds,
          onValueChange = { playerPreferences.skipOutroSeconds.set(it) },
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(onClick = onDismissRequest) {
            Text("关闭")
          }
        }
      }
    }
  }
}

/**
 * One numeric setting row: label, decrement button, editable value, increment button.
 * The stepper buttons keep the row fully usable with a D-pad even when no IME is available;
 * the text field allows typing an exact value.
 */
@Composable
private fun SecondsRow(
  label: String,
  value: Int,
  onValueChange: (Int) -> Unit,
) {
  var text by remember(value) { mutableStateOf(value.toString()) }

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = label,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.titleMedium,
    )
    IconButton(onClick = { onValueChange((value - 5).coerceAtLeast(0)) }) {
      Icon(Icons.Default.Remove, contentDescription = "减少")
    }
    OutlinedTextField(
      value = text,
      onValueChange = { input ->
        val digits = input.filter { it.isDigit() }.take(4)
        text = digits
        digits.toIntOrNull()?.let { onValueChange(it.coerceIn(0, 3600)) }
      },
      singleLine = true,
      keyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions(
          keyboardType = KeyboardType.Number,
        ),
      textStyle =
        MaterialTheme.typography.bodyMedium.copy(
          textAlign = TextAlign.Center,
        ),
      modifier = Modifier.width(92.dp),
    )
    IconButton(onClick = { onValueChange((value + 5).coerceAtMost(3600)) }) {
      Icon(Icons.Default.Add, contentDescription = "增加")
    }
  }
}
