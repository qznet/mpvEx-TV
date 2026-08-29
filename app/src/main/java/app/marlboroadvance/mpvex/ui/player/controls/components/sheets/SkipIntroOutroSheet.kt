package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
 * button. Three rows, all remote-friendly:
 *
 * 1. Enable checkbox (own row, focusRequester here)
 * 2. Intro length in seconds  (label / minus / number field / plus)
 * 3. Outro length in seconds  (same shape)
 *
 * Values are committed to [PlayerPreferences] as they change, so the running player picks
 * them up on the next file (and the outro check reads them live).
 *
 * TV notes:
 * - Body uses [LazyColumn] (not Column) so the D-Pad can always reach the last row even
 *   if a row grows tall.
 * - [BackHandler] closes the dialog on the remote's Back/Return key — without it the sheet
 *   can only be dismissed by tapping outside, which is impossible with a D-Pad.
 * - The first focusable child (the enable row) holds the [FocusRequester] so focus lands
 *   on the toggle instead of being trapped on the [Surface].
 */
@Composable
fun SkipIntroOutroSheet(
  playerPreferences: PlayerPreferences,
  onDismissRequest: () -> Unit,
) {
  val enabled by playerPreferences.skipIntroOutroEnabled.collectAsState()
  val introSeconds by playerPreferences.skipIntroSeconds.collectAsState()
  val outroSeconds by playerPreferences.skipOutroSeconds.collectAsState()

  // Remote's Back/Return key dismisses the sheet.
  BackHandler(onBack = onDismissRequest)

  // Focus lands on the enable row once the dialog has settled.
  val enableFocusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) {
    delay(120)
    runCatching { enableFocusRequester.requestFocus() }
  }

  Dialog(onDismissRequest = onDismissRequest) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 4.dp,
      modifier = Modifier.fillMaxWidth(0.92f),
    ) {
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 4.dp),
      ) {
        item {
          Text(
            text = "跳过片头片尾",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
          )
        }
        item {
          Text(
            text = "开始播放时若处于片头范围内，则跳到片头结束处；播到片尾范围内则自动播放下一集。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        item { HorizontalDivider() }

        // 1. Enable
        item {
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .focusRequester(enableFocusRequester)
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
        }

        // 2. Intro seconds
        item {
          SecondsRow(
            label = "片头秒数",
            value = introSeconds,
            onValueChange = { playerPreferences.skipIntroSeconds.set(it) },
          )
        }

        // 3. Outro seconds
        item {
          SecondsRow(
            label = "片尾秒数",
            value = outroSeconds,
            onValueChange = { playerPreferences.skipOutroSeconds.set(it) },
          )
        }

        item {
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
}

/**
 * One numeric setting row: label, decrement button, editable value, increment button.
 * The stepper buttons keep the row fully usable with a D-Pad even when no IME is available;
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
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
