package com.hayeco.flipspaces.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hayeco.flipspaces.core.AppEntry
import com.hayeco.flipspaces.core.SlotGrid

/** The 2 x 3 slot map. Tappable on the phone; on the glasses pass a null [onSelect]. */
@Composable
fun SlotMap(
    grid: SlotGrid,
    selected: Int,
    occupied: Map<Int, AppEntry>,
    onSelect: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until grid.rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (column in 0 until grid.columns) {
                    val slot = grid.slots[row * grid.columns + column]
                    val app = occupied[slot.index]
                    val isSelected = slot.index == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(16f / 9f)
                            .background(
                                if (app != null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                                shape,
                            )
                            .border(
                                BorderStroke(
                                    if (isSelected) 2.dp else 1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                ),
                                shape,
                            )
                            .then(if (onSelect != null) Modifier.clickable { onSelect(slot.index) } else Modifier)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = app?.label ?: slot.label,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

