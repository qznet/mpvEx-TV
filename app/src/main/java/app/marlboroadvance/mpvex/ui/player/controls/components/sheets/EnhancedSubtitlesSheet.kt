@file:Suppress("ktlint:standard:no-wildcard-imports")

package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.core.graphics.toColorInt
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.presentation.components.ExposedTextDropDownMenu
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import app.marlboroadvance.mpvex.presentation.components.TintedSliderItem
import app.marlboroadvance.mpvex.ui.player.TrackNode
import app.marlboroadvance.mpvex.ui.player.controls.components.panels.SubtitlesBorderStyle
import app.marlboroadvance.mpvex.ui.player.controls.components.panels.copyAsArgb
import app.marlboroadvance.mpvex.ui.player.controls.components.panels.toColorHexString
import app.marlboroadvance.mpvex.ui.theme.spacing
import com.github.k1rakishou.fsaf.FileManager
import com.yubyf.truetypeparser.TTFFile
import `is`.xyz.mpv.MPVLib
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * 一级字幕菜单：点按播放界面 SUBTITLES 按钮后弹出。
 *
 * 与原 [SubtitlesSheet] 不同，本表把"用户可见"的字幕设置（显示开关 / 轨道 / 添加本地字幕 /
 * 字幕样式：字体、字号、底部间距、字体轮廓、文字颜色、强制覆盖 ASS）一次性铺开，省去二级面板跳转。
 *
 * 字幕延迟仍由长按 SUBTITLES 触发（即 [SubtitleDelayPanel]），菜单头部"更多时间"按钮提供等效入口。
 *
 * TV 适配：内容较长，使用 `LazyColumn`（`rememberLazyListState`）承载，原生支持遥控器 DPad
 * 聚焦项自动滚入视口，方向键可一路滚到底部（与原始 SubtitlesSheet/GenericTracksSheet 一致）。
 */

@SuppressLint("MutableCollectionMutableState", "UnrememberedMutableState")
@Composable
fun EnhancedSubtitlesSheet(
  tracks: ImmutableList<TrackNode>,
  onToggleSubtitle: (Int) -> Unit,
  isSubtitleSelected: (Int) -> Boolean,
  onAddSubtitle: () -> Unit,
  onRemoveSubtitle: (Int) -> Unit,
  onOpenOnlineSearch: () -> Unit,
  onOpenSubtitleDelay: () -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val preferences = koinInject<SubtitlesPreferences>()
  // `sid` 为 "no" 或非正整数 → 字幕未启用；其它情况视为正在显示。
  val sidRaw by MPVLib.propString["sid"].collectAsState()
  val subtitlesVisible by remember {
    derivedStateOf { (sidRaw?.toIntOrNull() ?: 0) > 0 }
  }

  PlayerSheet(onDismissRequest, modifier = modifier) {
    val listState = rememberLazyListState()
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxWidth(),
      contentPadding = PaddingValues(vertical = MaterialTheme.spacing.small),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
      item {
        // 0. 显示字幕 + 添加按钮 + 在线搜索/字幕延迟
        ShowAddSubtitleHeader(
          subtitlesVisible = subtitlesVisible,
          onToggleVisible = {
            if (subtitlesVisible) {
              MPVLib.setPropertyString("sid", "no")
            } else {
              // 打开：优先复用当前 sid；若 sid<=0 则挑第一条可见字幕
              val pick = tracks.firstOrNull { isSubtitleSelected(it.id) } ?: tracks.firstOrNull()
              if (pick != null) {
                MPVLib.setPropertyInt("sid", pick.id)
              }
            }
          },
          onAdd = onAddSubtitle,
          onOnlineSearch = onOpenOnlineSearch,
          onDelay = onOpenSubtitleDelay,
        )
      }

      item {
        // 1. 字幕轨道
        SubtitleTracksSection(
          tracks = tracks,
          isSelected = isSubtitleSelected,
          onToggle = onToggleSubtitle,
          onRemove = onRemoveSubtitle,
        )
      }

      item {
        // 2. 字幕样式（字体/字号/底部间距/字体轮廓/文字颜色/强制覆盖 ASS）
        SubtitleStyleSection(preferences = preferences)
      }
    }
  }
}

@Composable
private fun ShowAddSubtitleHeader(
  subtitlesVisible: Boolean,
  onToggleVisible: () -> Unit,
  onAdd: () -> Unit,
  onOnlineSearch: () -> Unit,
  onDelay: () -> Unit,
) {
  Column {
    Row(
      Modifier
        .fillMaxWidth()
                .clickable(onClick = onToggleVisible)
        .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.smaller),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    ) {
      Icon(
        if (subtitlesVisible) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionOff,
        null,
        modifier = Modifier.size(28.dp),
      )
      Text(
        text = stringResource(R.string.player_sheets_sub_show_subtitles),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.weight(1f),
      )
      Switch(checked = subtitlesVisible, onCheckedChange = null)
    }
    Row(
      Modifier
        .fillMaxWidth()
                .clickable(onClick = onAdd)
        .height(56.dp)
        .padding(horizontal = MaterialTheme.spacing.medium),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    ) {
      Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(24.dp))
      Text(
        text = stringResource(R.string.player_sheets_add_ext_sub),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.weight(1f),
      )
      IconButton(onClick = onOnlineSearch) {
        Icon(Icons.Default.Search, contentDescription = null)
      }
      IconButton(onClick = onDelay) {
        Icon(Icons.Default.MoreTime, contentDescription = null)
      }
    }
  }
}

@Composable
private fun SubtitleTracksSection(
  tracks: ImmutableList<TrackNode>,
  isSelected: (Int) -> Boolean,
  onToggle: (Int) -> Unit,
  onRemove: (Int) -> Unit,
) {
  Column {
    Text(
      text = stringResource(R.string.player_sheets_sub_tracks_section),
      modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontWeight = FontWeight.SemiBold,
    )
    val internal = tracks.filter { it.external != true }
    val external = tracks.filter { it.external == true }
    if (internal.isEmpty() && external.isEmpty()) {
      Text(
        text = stringResource(R.string.player_sheets_sub_no_tracks_available),
        modifier = Modifier.padding(
          horizontal = MaterialTheme.spacing.medium,
          vertical = MaterialTheme.spacing.smaller,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      if (internal.isNotEmpty()) {
        TrackGroupLabel(text = stringResource(R.string.player_sheets_sub_embedded_subtitles))
      }
      internal.forEach { track ->
        SubtitleTrackRow(
          title = getTrackTitle(track),
          isSelected = isSelected(track.id),
          isExternal = false,
          onToggle = { onToggle(track.id) },
          onRemove = null,
        )
      }
      if (external.isNotEmpty() && internal.isNotEmpty()) {
        TrackGroupLabel(text = stringResource(R.string.player_sheets_sub_external_subtitles))
      }
      external.forEach { track ->
        SubtitleTrackRow(
          title = getTrackTitle(track),
          isSelected = isSelected(track.id),
          isExternal = true,
          onToggle = { onToggle(track.id) },
          onRemove = { onRemove(track.id) },
        )
      }
    }
  }
}

@Composable
private fun TrackGroupLabel(text: String) {
  Text(
    text = text,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.Bold,
  )
}

@Composable
private fun SubtitleTrackRow(
  title: String,
  isSelected: Boolean,
  isExternal: Boolean,
  onToggle: () -> Unit,
  onRemove: (() -> Unit)?,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
            .clickable(onClick = onToggle)
      .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
  ) {
    Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
    Text(
      title,
      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
      modifier = Modifier.weight(1f),
    )
    if (isExternal && onRemove != null) {
      IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = null) }
    }
  }
}

@SuppressLint("MutableCollectionMutableState")
@Composable
private fun SubtitleStyleSection(preferences: SubtitlesPreferences) {
  val context = LocalContext.current
  val fileManager = koinInject<FileManager>()
  val fonts = remember { mutableListOf("Default") }
  val font by MPVLib.propString["sub-font"].collectAsState()
  val fontSize by MPVLib.propInt["sub-font-size"].collectAsState()
  // 底部间距映射到 mpv 的 sub-pos（0=顶部, 100=视频底, 可到 150 进入底部黑边）。
  // 注意：旧实现误用 sub-margin-y，在 vo=mediacodec_embed 下 sub-margin-y=0 会使字幕被裁掉/消失；
  // 这里与 SubtitleSettingsMiscellaneousCard 保持一致用 sub-pos，避免“底部间距=0 字幕消失”。
  val subPos by MPVLib.propInt["sub-pos"].collectAsState()
  val mpvBorderStyle by MPVLib.propString["sub-border-style"].collectAsState()
  val borderStyle by remember {
    derivedStateOf {
      SubtitlesBorderStyle.entries.firstOrNull { it.value == mpvBorderStyle }
        ?: SubtitlesBorderStyle.OutlineAndShadow
    }
  }
  var fontsLoadingIndicator by remember {
    val indicator: (@Composable () -> Unit) = { CircularProgressIndicator(Modifier.size(28.dp)) }
    mutableStateOf<(@Composable () -> Unit)?>(indicator)
  }
  LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) {
      val fontsDir = fileManager.fromPath(context.filesDir.path + "/fonts")
      if (fileManager.exists(fontsDir)) {
        fonts.addAll(
          fileManager
            .listFiles(fontsDir)
            .filter {
              fileManager.isFile(it) &&
                fileManager.getName(it).lowercase().matches(".*\\.[ot]tf$".toRegex())
            }
            .mapNotNull {
              runCatching {
                TTFFile.open(fileManager.getInputStream(it) ?: return@mapNotNull null).families.values.first()
              }.getOrNull()
            }
            .distinct(),
        )
      }
      fontsLoadingIndicator = null
    }
  }

  var overrideAssSubs by remember {
    mutableStateOf(MPVLib.getPropertyString("sub-ass-override") == "force")
  }

  Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller)) {
    // 节标题
    Text(
      text = stringResource(R.string.player_sheets_sub_style_section),
      modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontWeight = FontWeight.SemiBold,
    )

    // 强制覆盖 ASS 字幕样式
    Row(
      Modifier
        .fillMaxWidth()
                .clickable {
          overrideAssSubs = !overrideAssSubs
          preferences.overrideAssSubs.set(overrideAssSubs)
          MPVLib.setPropertyString("sub-ass-override", if (overrideAssSubs) "force" else "scale")
          MPVLib.setPropertyString("secondary-sub-ass-override", if (overrideAssSubs) "force" else "scale")
        }
        .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.smaller),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
      Icon(Icons.Default.Style, null, modifier = Modifier.size(28.dp))
      Text(
        text = stringResource(R.string.player_sheets_sub_override_ass),
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyLarge,
      )
      Switch(checked = overrideAssSubs, onCheckedChange = null)
    }

    // 字体下拉
    Row(
      Modifier
        .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
      Icon(
        painterResource(R.drawable.outline_brand_family_24),
        null,
        modifier = Modifier.size(28.dp),
      )
      Box(Modifier.weight(1f)) {
        ExposedTextDropDownMenu(
          selectedValue = font.orEmpty().ifEmpty { "Default" },
          options = fonts.toImmutableList(),
          label = stringResource(R.string.player_sheets_sub_typography_font),
          onValueChangedEvent = {
            val actualFont = if (it == "Default") "" else it
            preferences.font.set(actualFont)
            MPVLib.setPropertyString("sub-font", actualFont)
            MPVLib.setPropertyString("secondary-sub-font", actualFont)
          },
          leadingIcon = fontsLoadingIndicator,
        )
      }
    }

    // 字号
    LabeledSliderRow(
      icon = { Icon(Icons.Default.FormatSize, null, modifier = Modifier.size(28.dp)) },
      label = stringResource(R.string.player_sheets_sub_typography_font_size),
      value = fontSize ?: preferences.fontSize.get(),
      min = 1,
      max = 100,
      valueText = (fontSize ?: preferences.fontSize.get()).toString(),
      onChange = {
        preferences.fontSize.set(it)
        MPVLib.setPropertyInt("sub-font-size", it)
      },
    )

    // 底部间距 (sub-pos)
    LabeledSliderRow(
      icon = { Icon(Icons.Default.Height, null, modifier = Modifier.size(28.dp)) },
      label = stringResource(R.string.player_sheets_sub_bottom_padding),
      value = subPos ?: preferences.subPos.get(),
      min = 0,
      max = 150,
      valueText = (subPos ?: preferences.subPos.get()).toString(),
      onChange = {
        preferences.subPos.set(it)
        MPVLib.setPropertyInt("sub-pos", it)
      },
    )

    // 字体轮廓 segmented
    Row(
      Modifier
        .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
      Icon(Icons.Default.Palette, null, modifier = Modifier.size(28.dp))
      Column(Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.player_sheets_subtitles_border_style),
          style = MaterialTheme.typography.bodyMedium,
        )
        Row(
          Modifier
            .fillMaxWidth()
            .padding(top = MaterialTheme.spacing.extraSmall)
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
          SubtitlesBorderStyle.entries.forEach { style ->
            SegmentedChip(
              selected = borderStyle == style,
              label = stringResource(style.titleRes),
              onClick = {
                preferences.borderStyle.set(style)
                MPVLib.setPropertyString("sub-border-style", style.value)
              },
            )
          }
        }
      }
    }

    // 文字颜色
    SubtitleTextColorBlock(preferences)
  }
}

@Composable
private fun SegmentedChip(
  selected: Boolean,
  label: String,
  onClick: () -> Unit,
) {
  val bg =
    if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
  val fg =
    if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
  Box(
    modifier = Modifier
            .clickable(onClick = onClick)
      .background(bg, shape = RoundedCornerShape(20.dp))
      .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
  ) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
  }
}

@Composable
private fun LabeledSliderRow(
  icon: @Composable () -> Unit,
  label: String,
  value: Int,
  min: Int,
  max: Int,
  valueText: String,
  onChange: (Int) -> Unit,
) {
  val haptic = LocalHapticFeedback.current
  Row(
    Modifier
      .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.medium),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
  ) {
    icon()
    Column(Modifier.weight(0.5f)) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      Text(valueText)
    }
    Slider(
      value = value.toFloat(),
      onValueChange = {
        val newValue = it.toInt()
        if (newValue != value) {
          onChange(newValue)
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
      },
      modifier = Modifier.weight(1.5f),
      valueRange = min.toFloat()..max.toFloat(),
      steps = (max - min).coerceAtLeast(0),
    )
  }
}

/** 文字颜色：当前色预览 + 4 个预设 + ARGB 滑块。 */
@Composable
private fun SubtitleTextColorBlock(preferences: SubtitlesPreferences) {
  var currentColor by remember {
    mutableIntStateOf(
      try {
        MPVLib.getPropertyString("sub-color")?.uppercase()?.toColorInt() ?: preferences.textColor.get()
      } catch (_: Throwable) {
        preferences.textColor.get()
      },
    )
  }
  LaunchedEffect(Unit) {
    try {
      MPVLib.getPropertyString("sub-color")?.uppercase()?.toColorInt()?.let { currentColor = it }
    } catch (_: Throwable) {
    }
  }

  fun applyColor(argb: Int) {
    currentColor = argb
    preferences.textColor.set(argb)
    MPVLib.setPropertyString("sub-color", argb.toColorHexString())
  }

  Column(
    Modifier
      .padding(horizontal = MaterialTheme.spacing.medium)
      ,
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = stringResource(R.string.player_sheets_subtitles_color_text),
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyMedium,
      )
      ColorSwatch(currentColor)
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
      modifier = Modifier.fillMaxWidth(),
    ) {
      val presets = listOf(
        0xFFFFFFFF.toInt(), // 白
        0xFF66BB6A.toInt(), // 绿
        0xFF42A5F5.toInt(), // 蓝
        0xFFFF7043.toInt(), // 橙
      )
      presets.forEach { preset ->
        ColorPresetChip(
          color = preset,
          selected = currentColor.copyAsArgb(alpha = 255) == preset.copyAsArgb(alpha = 255),
          onClick = { applyColor(preset) },
        )
      }
    }
    TintedSliderItem(
      label = "R",
      value = currentColor.red,
      valueText = currentColor.red.toString(),
      onChange = { v -> applyColor(currentColor.copyAsArgb(red = v)) },
      max = 255,
      tint = Color.Red,
      modifier = Modifier,
    )
    TintedSliderItem(
      label = "G",
      value = currentColor.green,
      valueText = currentColor.green.toString(),
      onChange = { v -> applyColor(currentColor.copyAsArgb(green = v)) },
      max = 255,
      tint = Color.Green,
      modifier = Modifier,
    )
    TintedSliderItem(
      label = "B",
      value = currentColor.blue,
      valueText = currentColor.blue.toString(),
      onChange = { v -> applyColor(currentColor.copyAsArgb(blue = v)) },
      max = 255,
      tint = Color.Blue,
      modifier = Modifier,
    )
    TintedSliderItem(
      label = "A",
      value = currentColor.alpha,
      valueText = currentColor.alpha.toString(),
      onChange = { v -> applyColor(currentColor.copyAsArgb(alpha = v)) },
      max = 255,
      tint = Color.White,
      modifier = Modifier,
    )
  }
}

@Composable
private fun ColorSwatch(argb: Int) {
  val r = (argb shr 16) and 0xFF
  val g = (argb shr 8) and 0xFF
  val b = argb and 0xFF
  val a = (argb shr 24) and 0xFF
  Box(
    Modifier
      .size(28.dp)
      .background(Color(r, g, b, a), shape = CircleShape)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
  )
}

@Composable
private fun ColorPresetChip(color: Int, selected: Boolean, onClick: () -> Unit) {
  val r = (color shr 16) and 0xFF
  val g = (color shr 8) and 0xFF
  val b = color and 0xFF
  Box(
    Modifier
            .size(36.dp)
      .background(Color(r, g, b), shape = CircleShape)
      .border(
        width = if (selected) 3.dp else 1.dp,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        shape = CircleShape,
      )
      .clickable(onClick = onClick),
  )
}
