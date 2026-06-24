package app.marlboroadvance.mpvex.ui.browser.cards

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.domain.network.NetworkConnection

@Composable
fun NetworkConnectionCard(
  connection: NetworkConnection,
  onConnect: (NetworkConnection) -> Unit,
  onDisconnect: (NetworkConnection) -> Unit,
  onEdit: (NetworkConnection) -> Unit,
  onCopy: (NetworkConnection) -> Unit,
  onDelete: (NetworkConnection) -> Unit,
  onBrowse: (NetworkConnection) -> Unit,
  onAutoConnectChange: (NetworkConnection, Boolean) -> Unit,
  modifier: Modifier = Modifier,
  isConnected: Boolean = false,
  isConnecting: Boolean = false,
  error: String? = null,
) {
  Card(
    modifier = modifier
      .fillMaxWidth()
      .widthIn(max = 500.dp)
      .then(
        // Make the entire card focusable and clickable via DPAD when connected
        // This allows TV remote users to just hit OK on the card to Browse
        if (isConnected) {
          Modifier.focusable()
        } else {
          Modifier
        }
      ),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ),
    onClick = {
      // DPAD OK on the whole card: Browse if connected, Connect if not
      if (isConnected) {
        onBrowse(connection)
      } else if (!isConnecting) {
        onConnect(connection)
      }
    },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
    ) {
      // Header with name and actions
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = connection.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "${connection.protocol.displayName} • ${connection.host}:${connection.port}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        Row {
          IconButton(onClick = { onCopy(connection) }) {
            Icon(
              Icons.Filled.ContentCopy,
              contentDescription = "Copy",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(onClick = { onEdit(connection) }) {
            Icon(
              Icons.Filled.Edit,
              contentDescription = "Edit",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(onClick = { onDelete(connection) }) {
            Icon(
              Icons.Filled.Delete,
              contentDescription = "Delete",
              tint = MaterialTheme.colorScheme.error,
            )
          }
        }
      }

      // Connection details
      if (connection.path != "/") {
        Text(
          text = "Path: ${connection.path}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      if (connection.username.isNotEmpty() && !connection.isAnonymous) {
        Text(
          text = "User: ${connection.username}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      // Error message
      if (error != null) {
        Text(
          text = "Error: $error",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 8.dp),
        )
      }

      // Auto-connect checkbox
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Checkbox(
          checked = connection.autoConnect,
          onCheckedChange = { checked ->
            onAutoConnectChange(connection, checked)
          },
          modifier = Modifier.size(32.dp).padding(end = 4.dp)
        )
        Text(
          text = "Auto-connect",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // Connection button - single action per card for TV remote
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 8.dp),
        horizontalArrangement = Arrangement.End,
      ) {
        when {
          isConnecting -> {
            FilledTonalButton(
              onClick = { },
              enabled = false,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                CircularProgressIndicator(
                  modifier = Modifier.size(16.dp),
                  strokeWidth = 2.dp,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                Text(
                  "Connecting",
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
              }
            }
          }

          isConnected -> {
            // Browse button only - Disconnect removed for TV remote simplicity
            FilledTonalButton(
              onClick = { onBrowse(connection) },
              colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
              ),
            ) {
              Icon(
                Icons.Filled.FolderOpen,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
              )
              Text("Browse")
            }
          }

          else -> {
            FilledTonalButton(
              onClick = { onConnect(connection) },
              colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
              ),
            ) {
              Icon(
                Icons.Filled.Link,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
              )
              Text("Connect")
            }
          }
        }
      }
    }
  }
}
