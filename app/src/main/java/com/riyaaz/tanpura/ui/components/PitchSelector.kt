package com.riyaaz.tanpura.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riyaaz.tanpura.model.Pitch

/**
 * Sa selector: the current pitch shown large, arrows for single-semitone steps,
 * and a scrolling strip of every available Sa so a known pitch is one tap away.
 */
@Composable
fun PitchSelector(
    saMidi: Int,
    fineCents: Float,
    onSaChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = remember { (Pitch.MIN_SA..Pitch.MAX_SA).toList() }
    val listState: LazyListState = rememberLazyListState()

    LaunchedEffect(saMidi) {
        val index = (saMidi - Pitch.MIN_SA).coerceIn(0, options.lastIndex)
        listState.animateScrollToItem(index.coerceAtLeast(0), scrollOffset = -220)
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(
                onClick = { onSaChange((saMidi - 1).coerceAtLeast(Pitch.MIN_SA)) },
                enabled = saMidi > Pitch.MIN_SA,
            ) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Lower Sa by a semitone")
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    text = Pitch.noteName(saMidi),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = buildString {
                        append("Sa · ")
                        append(String.format("%.2f Hz", Pitch.frequency(saMidi, fineCents)))
                        if (fineCents != 0f) append("  ${Pitch.formatCents(fineCents)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { onSaChange((saMidi + 1).coerceAtMost(Pitch.MAX_SA)) },
                enabled = saMidi < Pitch.MAX_SA,
            ) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Raise Sa by a semitone")
            }
        }

        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
        ) {
            items(items = options, key = { it }) { midi ->
                val selected = midi == saMidi
                Box(
                    modifier = Modifier
                        .size(width = 52.dp, height = 40.dp)
                        .background(
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(10.dp),
                        )
                        .border(
                            width = if (selected) 1.5.dp else 0.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onSaChange(midi) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = Pitch.noteName(midi),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
