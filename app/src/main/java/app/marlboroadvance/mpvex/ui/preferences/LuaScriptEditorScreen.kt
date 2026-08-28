package app.marlboroadvance.mpvex.ui.preferences

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.ConfirmDialog
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.utils.ScriptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * Create / edit a single Lua or JS script.
 *
 * Works against whichever script location is active (SAF tree or plain path) via
 * [ScriptRepository], so behaviour matches the rest of the app.
 *
 * @param scriptName null creates a new script; otherwise the existing file is opened.
 */
@Serializable
data class LuaScriptEditorScreen(
  val scriptName: String?,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val preferences = koinInject<AdvancedPreferences>()
    val scope = rememberCoroutineScope()

    val mpvConfStorageLocation by preferences.mpvConfStorageUri.collectAsState()
    val defaultScriptsPath by preferences.mpvScriptsDir.collectAsState()

    val isNewScript = scriptName == null
    val title = if (isNewScript) "新建脚本" else "编辑脚本"

    var scriptContent by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf(scriptName ?: "") }
    var hasUnsavedChanges by remember { mutableStateOf(isNewScript) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Load the existing script's text.
    LaunchedEffect(scriptName, mpvConfStorageLocation, defaultScriptsPath) {
      if (scriptName == null) return@LaunchedEffect
      val content =
        withContext(Dispatchers.IO) {
          ScriptRepository.readScript(context, preferences, scriptName)
        }
      if (content != null) {
        scriptContent = content
        hasUnsavedChanges = false
      }
    }

    /** Ensures the name ends in a script extension mpv understands. */
    fun resolveFileName(): String {
      val trimmed = fileName.trim()
      if (trimmed.isBlank()) return ""
      val ext = trimmed.substringAfterLast('.', "").lowercase()
      return if (ext in ScriptRepository.SCRIPT_EXTENSIONS) trimmed else "$trimmed.lua"
    }

    fun saveScript() {
      val finalFileName = resolveFileName()
      if (finalFileName.isBlank()) {
        Toast.makeText(context, "请输入脚本名称", Toast.LENGTH_SHORT).show()
        return
      }

      scope.launch(Dispatchers.IO) {
        val ok =
          ScriptRepository.writeScript(context, preferences, finalFileName, scriptContent)

        // Renaming: drop the old file once the new one is safely written.
        if (ok && !isNewScript && scriptName != null && scriptName != finalFileName) {
          ScriptRepository.deleteScript(context, preferences, scriptName)
          val selected = preferences.selectedLuaScripts.get()
          if (selected.contains(scriptName)) {
            preferences.selectedLuaScripts.set(selected - scriptName)
          }
        }

        withContext(Dispatchers.Main) {
          if (ok) {
            hasUnsavedChanges = false
            Toast.makeText(context, "$finalFileName 已保存", Toast.LENGTH_SHORT).show()
            backStack.removeLastOrNull()
          } else {
            Toast.makeText(context, "保存失败，请检查脚本目录的写入权限", Toast.LENGTH_LONG).show()
          }
        }
      }
    }

    fun shareScript() {
      if (isNewScript || scriptName == null) {
        Toast.makeText(context, "请先保存脚本再分享", Toast.LENGTH_SHORT).show()
        return
      }
      scope.launch(Dispatchers.IO) {
        val cacheFile = ScriptRepository.copyToCache(context, preferences, scriptName)
        withContext(Dispatchers.Main) {
          if (cacheFile == null) {
            Toast.makeText(context, "无法读取脚本：$scriptName", Toast.LENGTH_LONG).show()
            return@withContext
          }
          runCatching {
            val uri =
              FileProvider.getUriForFile(context, "${context.packageName}.provider", cacheFile)
            val shareIntent =
              Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, scriptName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              }
            context.startActivity(Intent.createChooser(shareIntent, "分享 $scriptName"))
          }.onFailure { e ->
            Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_LONG).show()
          }
        }
      }
    }

    fun deleteScript() {
      if (isNewScript || scriptName == null) {
        backStack.removeLastOrNull()
        return
      }
      scope.launch(Dispatchers.IO) {
        val deleted = ScriptRepository.deleteScript(context, preferences, scriptName)
        if (deleted) {
          val selected = preferences.selectedLuaScripts.get()
          if (selected.contains(scriptName)) {
            preferences.selectedLuaScripts.set(selected - scriptName)
          }
        }
        withContext(Dispatchers.Main) {
          if (deleted) {
            Toast.makeText(context, "$scriptName 已删除", Toast.LENGTH_SHORT).show()
            backStack.removeLastOrNull()
          } else {
            Toast.makeText(context, "删除失败：$scriptName", Toast.LENGTH_LONG).show()
          }
        }
      }
    }

    Column(modifier = Modifier.fillMaxSize()) {
      TopAppBar(
        title = {
          Column {
            BasicTextField(
              value = fileName,
              onValueChange = {
                fileName = it
                hasUnsavedChanges = true
              },
              singleLine = true,
              textStyle =
                MaterialTheme.typography.titleLarge.copy(
                  fontWeight = FontWeight.ExtraBold,
                  color = MaterialTheme.colorScheme.primary,
                ),
              cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
              decorationBox = { innerTextField ->
                Box {
                  if (fileName.isEmpty()) {
                    Text(
                      text = "脚本名称",
                      style =
                        MaterialTheme.typography.titleLarge.copy(
                          fontWeight = FontWeight.ExtraBold,
                          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        ),
                    )
                  }
                  innerTextField()
                }
              },
            )
            Text(
              text = if (hasUnsavedChanges) "$title · 未保存" else title,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.secondary,
            )
          }
        },
        navigationIcon = {
          IconButton(onClick = backStack::removeLastOrNull) {
            Icon(
              Icons.AutoMirrored.Outlined.ArrowBack,
              contentDescription = "返回",
              tint = MaterialTheme.colorScheme.secondary,
            )
          }
        },
        actions = {
          if (!isNewScript) {
            IconButton(
              onClick = { shareScript() },
              modifier = Modifier.padding(horizontal = 4.dp).size(40.dp),
              colors =
                IconButtonDefaults.iconButtonColors(
                  containerColor = MaterialTheme.colorScheme.surfaceVariant,
                  contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
              shape = RoundedCornerShape(8.dp),
            ) {
              Icon(Icons.Outlined.Share, contentDescription = "分享")
            }

            IconButton(
              onClick = { showDeleteDialog = true },
              modifier = Modifier.padding(horizontal = 4.dp).size(40.dp),
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

          val canSave = hasUnsavedChanges && fileName.isNotBlank()
          IconButton(
            onClick = { saveScript() },
            enabled = canSave,
            modifier = Modifier.padding(horizontal = 4.dp).size(40.dp),
            colors =
              IconButtonDefaults.iconButtonColors(
                containerColor =
                  if (canSave) {
                    MaterialTheme.colorScheme.primaryContainer
                  } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                  },
                contentColor =
                  if (canSave) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                  } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                  },
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
              ),
            shape = RoundedCornerShape(8.dp),
          ) {
            Icon(Icons.Outlined.Check, contentDescription = "保存")
          }
        },
      )

      val scrollState = rememberScrollState()
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .weight(1f)
            .imePadding(),
      ) {
        BasicTextField(
          value = scriptContent,
          onValueChange = {
            scriptContent = it
            hasUnsavedChanges = true
          },
          modifier =
            Modifier
              .fillMaxSize()
              .verticalScroll(scrollState)
              .padding(horizontal = 16.dp, vertical = 12.dp),
          textStyle =
            TextStyle(
              fontFamily = FontFamily.Monospace,
              fontSize = 14.sp,
              color = MaterialTheme.colorScheme.onSurface,
            ),
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
      }
    }

    if (showDeleteDialog) {
      ConfirmDialog(
        title = "删除脚本？",
        subtitle = "确定要删除「${scriptName ?: fileName}」吗？此操作无法撤销。",
        onConfirm = {
          deleteScript()
          showDeleteDialog = false
        },
        onCancel = { showDeleteDialog = false },
      )
    }
  }
}
