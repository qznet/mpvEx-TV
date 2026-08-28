package app.marlboroadvance.mpvex.ui.preferences

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.utils.ScriptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * Script manager: lists every Lua/JS script found in the configured script directory and lets
 * the user tick the ones mpv should load.
 *
 * TV note: each script is its own [items] entry so the D-Pad can scroll to any row, and the
 * whole row is the toggle target (the [Checkbox] is display-only with `onCheckedChange = null`
 * so it never steals focus from the row).
 */
@Serializable
object LuaScriptsScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val preferences = koinInject<AdvancedPreferences>()
    val scope = rememberCoroutineScope()

    val mpvConfStorageLocation by preferences.mpvConfStorageUri.collectAsState()
    val defaultScriptsPath by preferences.mpvScriptsDir.collectAsState()
    val selectedScripts by preferences.selectedLuaScripts.collectAsState()
    val enableLuaScripts by preferences.enableLuaScripts.collectAsState()

    var availableScripts by remember { mutableStateOf<List<String>>(emptyList()) }
    var locationHint by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    // Reload whenever the source of scripts could have changed.
    LaunchedEffect(mpvConfStorageLocation, defaultScriptsPath) {
      isLoading = true
      val (scripts, hint) =
        withContext(Dispatchers.IO) {
          ScriptRepository.listScripts(context, preferences) to
            ScriptRepository.describe(context, preferences)
        }
      availableScripts = scripts
      locationHint = hint
      isLoading = false

      // Drop selections pointing at scripts that no longer exist.
      val currentSelection = preferences.selectedLuaScripts.get()
      val validSelection = currentSelection.filter { it in scripts }
      if (validSelection.size != currentSelection.size) {
        preferences.selectedLuaScripts.set(validSelection.toSet())
      }
    }

    fun toggleScriptSelection(scriptName: String) {
      val newSelection =
        if (selectedScripts.contains(scriptName)) {
          selectedScripts - scriptName
        } else {
          selectedScripts + scriptName
        }
      preferences.selectedLuaScripts.set(newSelection)
    }

    fun shareScript(scriptName: String) {
      scope.launch(Dispatchers.IO) {
        val cacheFile = ScriptRepository.copyToCache(context, preferences, scriptName)
        withContext(Dispatchers.Main) {
          if (cacheFile == null) {
            Toast.makeText(context, "无法读取脚本：$scriptName", Toast.LENGTH_LONG).show()
            return@withContext
          }
          runCatching {
            val uri =
              FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                cacheFile,
              )
            val shareIntent =
              Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, scriptName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              }
            context.startActivity(Intent.createChooser(shareIntent, "分享脚本"))
          }.onFailure { e ->
            Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_LONG).show()
          }
        }
      }
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = "Lua 脚本",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
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
            IconButton(onClick = { backStack.add(LuaScriptEditorScreen(scriptName = null)) }) {
              Icon(
                Icons.Outlined.Add,
                contentDescription = "新建脚本",
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(bottom = 24.dp),
      ) {
        when {
          !enableLuaScripts -> {
            item {
              HintCard(
                title = "脚本功能未启用",
                body = "请返回「高级」设置页，先打开「启用 Lua 脚本」开关。",
              )
            }
          }

          isLoading -> {
            item {
              Box(modifier = Modifier.fillMaxWidth().padding(32.dp)) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
              }
            }
          }

          availableScripts.isEmpty() -> {
            item {
              Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Code,
                  contentDescription = null,
                  modifier = Modifier.size(48.dp),
                  tint = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                  text = "未找到脚本",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = "将 .lua 或 .js 文件放入脚本目录：\n$locationHint",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
                )
              }
            }
          }

          else -> {
            item {
              Text(
                text = "脚本目录：$locationHint",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
              )
            }

            items(availableScripts, key = { it }) { scriptName ->
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clickable { toggleScriptSelection(scriptName) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                // Display-only: the row owns the toggle so the D-Pad has a single target.
                Checkbox(
                  checked = selectedScripts.contains(scriptName),
                  onCheckedChange = null,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                  text = scriptName,
                  style = MaterialTheme.typography.bodyLarge,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { shareScript(scriptName) }) {
                  Icon(
                    Icons.Outlined.Share,
                    contentDescription = "分享",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                IconButton(
                  onClick = { backStack.add(LuaScriptEditorScreen(scriptName = scriptName)) },
                ) {
                  Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "编辑",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun HintCard(
  title: String,
  body: String,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = body,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}
