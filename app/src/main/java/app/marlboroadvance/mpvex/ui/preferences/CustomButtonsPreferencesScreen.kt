package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.preferences.CustomButton
import app.marlboroadvance.mpvex.preferences.CustomButtonSlots
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.ConfirmDialog
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * Fixed slot labels for the eight player-overlay Lua buttons.
 * Order: L1..L4 (left column) then R1..R4 (right column).
 */
private fun slotLabel(index: Int): String {
  require(index in 0 until CustomButtonSlots.SLOT_COUNT)
  return if (index < 4) "L${index + 1}" else "R${index - 3}"
}

@Serializable
object CustomButtonsPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val playerPreferences = koinInject<PlayerPreferences>()
    val slots by playerPreferences.customButtons.collectAsState()
    val bottomMargin by playerPreferences.customButtonsBottomMargin.collectAsState()

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = "自定义按钮",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = backstack::removeLastOrNull) {
              Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      LazyColumn(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberLazyListState(),
      ) {
        item {
          Text(
            text = "最多 8 个固定槽位（L1–L4 左列，R1–R4 右列）。每个按钮可填入 Lua 代码，点击/长按播放器按钮时执行。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
          )
        }

        items(CustomButtonSlots.SLOT_COUNT) { index ->
          val button = slots.slots.getOrNull(index)
          ListItem(
            modifier =
              Modifier
                .fillMaxWidth()
                .clickable { backstack.add(CustomButtonEditorScreen(slotIndex = index)) },
            headlineContent = {
              Text(
                text = "${slotLabel(index)}　${button?.title ?: "（空）"}",
                fontWeight = FontWeight.Bold,
                color = if (button == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
              )
            },
            supportingContent = {
              if (button != null) {
                val parts = mutableListOf<String>()
                if (button.content.isNotBlank()) parts += "点击"
                if (button.longPressContent.isNotBlank()) parts += "长按"
                if (button.onStartup.isNotBlank()) parts += "启动"
                Text(
                  text = if (parts.isEmpty()) "无动作" else "动作：${parts.joinToString(" / ")}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.outline,
                )
              }
            },
            trailingContent = {
              if (button != null) {
                Text(
                  text = if (button.enabled) "已启用" else "已禁用",
                  style = MaterialTheme.typography.labelMedium,
                  color =
                    if (button.enabled) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.outline
                    },
                )
              }
            },
          )
        }

        item {
          Column(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, top = 16.dp, bottom = 8.dp),
          ) {
            Text(
              text = "播放器内按钮底边距: ${bottomMargin}dp",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Slider(
              value = bottomMargin.toFloat(),
              onValueChange = { playerPreferences.customButtonsBottomMargin.set(it.toInt()) },
              valueRange = 0f..300f,
              steps = 60,
              modifier = Modifier.fillMaxWidth(),
            )
            Text(
              text = "仅控制播放界面上自定义按钮行的垂直位置（类似字幕底边距）。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.outline,
            )
          }
        }
      }
    }
  }
}

/**
 * Editor for a single slot. `slotIndex` is 0..7 (L1..L4, R1..R4).
 *
 * TV note: every interactive control is its own [LazyColumn] item so the D-Pad can reach it,
 * and the Lua code boxes own a bounded vertical scroll rather than relying on a sheet scroll.
 */
@Serializable
data class CustomButtonEditorScreen(
  val slotIndex: Int,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val playerPreferences = koinInject<PlayerPreferences>()
    val slots by playerPreferences.customButtons.collectAsState()

    val existing = slots.slots.getOrNull(slotIndex)

    var title by remember { mutableStateOf(existing?.title ?: "") }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var content by remember { mutableStateOf(existing?.content ?: "") }
    var longPressContent by remember { mutableStateOf(existing?.longPressContent ?: "") }
    var onStartup by remember { mutableStateOf(existing?.onStartup ?: "") }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(existing) {
      title = existing?.title ?: ""
      enabled = existing?.enabled ?: true
      content = existing?.content ?: ""
      longPressContent = existing?.longPressContent ?: ""
      onStartup = existing?.onStartup ?: ""
    }

    fun persist(button: CustomButton?) {
      val current = slots.slots.toMutableList()
      // Grow the list defensively if the stored count ever differs from SLOT_COUNT.
      while (current.size < CustomButtonSlots.SLOT_COUNT) current.add(null)
      current[slotIndex] = button
      playerPreferences.customButtons.set(CustomButtonSlots(current))
    }

    fun save() {
      val isEmpty =
        title.isBlank() && content.isBlank() && longPressContent.isBlank() && onStartup.isBlank()
      if (isEmpty) {
        persist(null)
      } else {
        val btn =
          if (existing != null) {
            existing.copy(
              title = title.trim().ifBlank { "${slotLabel(slotIndex)}" },
              enabled = enabled,
              content = content,
              longPressContent = longPressContent,
              onStartup = onStartup,
            )
          } else {
            CustomButton(
              title = title.trim().ifBlank { "${slotLabel(slotIndex)}" },
              enabled = enabled,
              content = content,
              longPressContent = longPressContent,
              onStartup = onStartup,
            )
          }
        persist(btn)
      }
      backstack.removeLastOrNull()
    }

    fun delete() {
      persist(null)
      backstack.removeLastOrNull()
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = "编辑按钮 ${slotLabel(slotIndex)}",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = backstack::removeLastOrNull) {
              Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
          actions = {
            if (existing != null) {
              IconButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.padding(horizontal = 4.dp),
                colors =
                  IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                  ),
                shape = RoundedCornerShape(8.dp),
              ) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除")
              }
            }
            IconButton(
              onClick = { save() },
              modifier = Modifier.padding(horizontal = 4.dp),
              colors =
                IconButtonDefaults.iconButtonColors(
                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
              shape = RoundedCornerShape(8.dp),
            ) {
              Icon(Icons.Outlined.Check, contentDescription = "保存")
            }
          },
        )
      },
    ) { padding ->
      LazyColumn(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberLazyListState(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
      ) {
        item {
          OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("按钮名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }

        item {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "在播放器上显示此按钮",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Switch(checked = enabled, onCheckedChange = { enabled = it })
          }
        }

        item {
          LuaCodeField(
            label = "点击执行（Lua）",
            hint = "例如：mp.osd_message(\"hello\")",
            value = content,
            onValueChange = { content = it },
          )
        }

        item {
          LuaCodeField(
            label = "长按执行（Lua）",
            hint = "留空则长按无动作",
            value = longPressContent,
            onValueChange = { longPressContent = it },
          )
        }

        item {
          LuaCodeField(
            label = "播放启动时执行（Lua）",
            hint = "每次打开播放器执行一次，可在此注册全局快捷键等",
            value = onStartup,
            onValueChange = { onStartup = it },
          )
        }
      }
    }

    if (showDeleteDialog) {
      ConfirmDialog(
        title = "删除此按钮？",
        subtitle = "将清空 ${slotLabel(slotIndex)} 槽位，此操作无法撤销。",
        onConfirm = {
          delete()
          showDeleteDialog = false
        },
        onCancel = { showDeleteDialog = false },
      )
    }
  }
}

/**
 * A monospace, bounded-height Lua source editor. The fixed height keeps the outer [LazyColumn]
 * item small enough that the D-Pad can scroll the whole editor, while the inner scroll handles
 * multi-line code editing once the field is focused.
 */
@Composable
private fun LuaCodeField(
  label: String,
  hint: String,
  value: String,
  onValueChange: (String) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.secondary,
      modifier = Modifier.padding(bottom = 6.dp),
    )
    val scrollState = rememberScrollState()
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      modifier =
        Modifier
          .fillMaxWidth()
          .height(160.dp)
          .background(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            RoundedCornerShape(10.dp),
          ).verticalScroll(scrollState)
          .padding(12.dp),
      textStyle =
        TextStyle(
          fontFamily = FontFamily.Monospace,
          fontSize = 13.sp,
          color = MaterialTheme.colorScheme.onSurface,
        ),
      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
      decorationBox = { innerTextField ->
        Box {
          if (value.isEmpty()) {
            Text(
              text = hint,
              style =
                TextStyle(
                  fontFamily = FontFamily.Monospace,
                  fontSize = 13.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                ),
            )
          }
          innerTextField()
        }
      },
    )
  }
}
