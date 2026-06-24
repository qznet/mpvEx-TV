package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.SignalWifiStatusbarConnectedNoInternet4
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.browser.components.BrowserTopBar
import app.marlboroadvance.mpvex.ui.browser.cards.NetworkConnectionCard
import app.marlboroadvance.mpvex.ui.browser.dialogs.AddConnectionSheet
import app.marlboroadvance.mpvex.ui.browser.dialogs.EditConnectionSheet
import app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable

@Serializable
object NetworkStreamingScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val viewModel: NetworkStreamingViewModel =
      viewModel(factory = NetworkStreamingViewModel.factory(context.applicationContext as android.app.Application))

    val connections by viewModel.connections.collectAsState()
    val connectionStatuses by viewModel.connectionStatuses.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf<NetworkConnection?>(null) }
    var copyingConnection by remember { mutableStateOf<NetworkConnection?>(null) }
    val navigationBarHeight = app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight.current

    // Track whether we've already auto-navigated to avoid repeated navigation
    var hasAutoNavigated by remember { mutableStateOf(false) }

    // Find the first connected connection (auto-connect order)
    val firstConnectedConnection by remember {
      derivedStateOf {
        connections.firstOrNull { conn ->
          connectionStatuses[conn.id]?.isConnected == true
        }
      }
    }

    // Check if any connection is still connecting (auto-connect in progress)
    val isAnyConnecting by remember {
      derivedStateOf {
        connections.any { conn ->
          connectionStatuses[conn.id]?.isConnecting == true
        }
      }
    }

    // Auto-navigate: if there's a connected connection, go directly to Browse
    // Wait for any in-progress auto-connections to finish first
    LaunchedEffect(firstConnectedConnection, isAnyConnecting, hasAutoNavigated) {
      if (!hasAutoNavigated && !isAnyConnecting && firstConnectedConnection != null) {
        hasAutoNavigated = true
        backstack.add(
          NetworkBrowserScreen(
            connectionId = firstConnectedConnection!!.id,
            connectionName = firstConnectedConnection!!.name,
            currentPath = "/",
          ),
        )
      }
    }

    // LazyGrid state for scroll tracking
    val gridState = rememberLazyGridState()

    Scaffold(
        topBar = {
          BrowserTopBar(
            title = stringResource(R.string.network),
            isInSelectionMode = false,
            selectedCount = 0,
            totalCount = 0,
            onBackClick = null, // No back button for network screen (root tab)
            onCancelSelection = { },
            onSortClick = null,
            onSearchClick = null,
            onSettingsClick = {
              backstack.add(app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen)
            },
            onAddConnectionClick = { showAddSheet = true },
            onDeleteClick = null,
            onRenameClick = null,
            isSingleSelection = false,
            onInfoClick = null,
            onShareClick = null,
            onPlayClick = null,
            onSelectAll = null,
            onInvertSelection = null,
            onDeselectAll = null,
          )
        },
    ) { padding ->
      LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 360.dp),
        state = gridState,
        modifier = Modifier
          .fillMaxSize()
          .padding(padding),
        contentPadding = PaddingValues(
          start = 16.dp, 
          end = 16.dp, 
          top = 16.dp, 
          bottom = navigationBarHeight
        ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
          // Local Network header
          item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
              text = stringResource(R.string.local_network),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(vertical = 8.dp),
            )
          }

          // Show empty state or connection list
          if (connections.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
              Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
              ) {
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  Icon(
                    imageVector = Icons.Rounded.SignalWifiStatusbarConnectedNoInternet4,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                  )
                  Spacer(modifier = Modifier.height(16.dp))
                  Text(
                    text = stringResource(R.string.no_network_connections),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Spacer(modifier = Modifier.height(8.dp))
                  Text(
                    text = stringResource(R.string.add_network_connections_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                  )
                }
              }
            }
          } else {
            items(connections, key = { it.id }) { connection ->
              val status = connectionStatuses[connection.id]
              Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                NetworkConnectionCard(
                  connection = connection,
                  onConnect = { conn ->
                    viewModel.connect(conn)
                  },
                  onDisconnect = { conn -> viewModel.disconnect(conn) },
                  onEdit = { conn -> editingConnection = conn },
                  onCopy = { conn -> copyingConnection = conn },
                  onDelete = { conn -> viewModel.deleteConnection(conn) },
                  onBrowse = { conn ->
                    // Navigate to browser screen if connected
                    if (status?.isConnected == true) {
                      backstack.add(
                        NetworkBrowserScreen(
                          connectionId = conn.id,
                          connectionName = conn.name,
                          currentPath = "/",
                        ),
                      )
                    }
                  },
                  onAutoConnectChange = { conn, autoConnect ->
                    viewModel.updateConnection(conn.copy(autoConnect = autoConnect))
                  },
                  isConnected = status?.isConnected ?: false,
                  isConnecting = status?.isConnecting ?: false,
                  error = status?.error,
                  modifier = Modifier,
                )
              }
            }
          }
        }

      // Add Connection Sheet
      AddConnectionSheet(
        isOpen = showAddSheet || copyingConnection != null,
        initialConnection = copyingConnection,
        onDismiss = { 
          showAddSheet = false
          copyingConnection = null
        },
        onSave = { connection ->
          viewModel.addConnection(connection)
          showAddSheet = false
          copyingConnection = null
        },
      )

      // Edit Connection Sheet
      editingConnection?.let { connection ->
        EditConnectionSheet(
          connection = connection,
          isOpen = true,
          onDismiss = { editingConnection = null },
          onSave = { updatedConnection ->
            viewModel.updateConnection(updatedConnection)
            editingConnection = null
          },
        )
      }
    }
  }
}
