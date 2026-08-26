package app.marlboroadvance.mpvex.ui.preferences

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.preferences.CustomButton
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import org.koin.compose.koinInject
import kotlinx.serialization.Serializable

@Serializable
object CustomButtonsPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val playerPreferences = koinInject<PlayerPreferences>()

    val buttons by playerPreferences.customButtons.collectAsState()
    val bottomMargin by playerPreferences.customButtonsBottomMargin.collectAsState()

    // Dialog state. editingId == null means "add new"; otherwise edit existing.
    var showDialog by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var draftName by remember { mutableStateOf("") }
    var draftCommand by remember { mutableStateOf("") }

    fun openAdd() {
      editingId = null
      draftName = ""
      draftCommand = ""
      showDialog = true
    }

    fun openEdit(button: CustomButton) {
      editingId = button.id
      draftName = button.label
      draftCommand = button.command
      showDialog = true
    }

    fun commitDialog() {
      val name = draftName.trim()
      val command = draftCommand.trim()
      if (name.isBlank() || command.isBlank()) {
        showDialog = false
        return
      }
      val current = buttons.toMutableList()
      if (editingId == null) {
        current.add(
          CustomButton(
            id = "btn_${System.nanoTime()}",
            label = name,
            command = command,
            enabled = true,
          ),
        )
      } else {
        val idx = current.indexOfFirst { it.id == editingId }
        if (idx >= 0) {
          current[idx] = current[idx].copy(label = name, command = command)
        }
      }
      playerPreferences.customButtons.set(current)
      showDialog = false
    }

    fun toggleEnabled(button: CustomButton) {
      playerPreferences.customButtons.set(
        buttons.map { if (it.id == button.id) it.copy(enabled = !it.enabled) else it },
      )
    }

    fun delete(button: CustomButton) {
      playerPreferences.customButtons.set(buttons.filter { it.id != button.id })
    }

    fun move(button: CustomButton, delta: Int) {
      val idx = buttons.indexOfFirst { it.id == button.id }
      val target = idx + delta
      if (idx < 0 || target < 0 || target >= buttons.size) return
      val current = buttons.toMutableList()
      val tmp = current[idx]
      current[idx] = current[target]
      current[target] = tmp
      playerPreferences.customButtons.set(current)
    }

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
      floatingActionButton = {
        FloatingActionButton(onClick = { openAdd() }) {
          Icon(Icons.Outlined.Add, contentDescription = "添加按钮")
        }
      },
    ) { padding ->
      LazyColumn(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (buttons.isEmpty()) {
          item {
            Text(
              text = "暂无自定义按钮，点击右下角 + 添加。命令为 mpv 命令，例如：seek 90、cycle pause、set speed 1.5",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.outline,
              modifier = Modifier.padding(16.dp),
            )
          }
        }

        items(buttons.size, key = { buttons[it].id }) { index ->
          val button = buttons[index]
          ListItem(
            modifier =
              Modifier
                .fillMaxWidth()
                .clickable { openEdit(button) },
            headlineContent = {
              Text(
                text = button.label,
                fontWeight = FontWeight.Bold,
              )
            },
            supportingContent = {
              Text(text = "命令: ${button.command}")
            },
            leadingContent = {
              androidx.compose.material3.Checkbox(
                checked = button.enabled,
                onCheckedChange = { toggleEnabled(button) },
              )
            },
            trailingContent = {
              Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { move(button, -1) }) {
                  Icon(Icons.Outlined.ArrowUpward, contentDescription = "上移")
                }
                IconButton(onClick = { move(button, 1) }) {
                  Icon(Icons.Outlined.ArrowDownward, contentDescription = "下移")
                }
                IconButton(onClick = { openEdit(button) }) {
                  Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = { delete(button) }) {
                  Icon(Icons.Outlined.Delete, contentDescription = "删除")
                }
              }
            },
          )
        }

        item {
          Column(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
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

    if (showDialog) {
      AlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text(text = if (editingId == null) "添加自定义按钮" else "编辑自定义按钮") },
        text = {
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            OutlinedTextField(
              value = draftName,
              onValueChange = { draftName = it },
              label = { Text("名称") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
              value = draftCommand,
              onValueChange = { draftCommand = it },
              label = { Text("mpv 命令") },
              placeholder = { Text("例如 seek 90") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        },
        confirmButton = {
          TextButton(onClick = { commitDialog() }) {
            Text("保存")
          }
        },
        dismissButton = {
          TextButton(onClick = { showDialog = false }) {
            Text("取消")
          }
        },
      )
    }
  }
}
