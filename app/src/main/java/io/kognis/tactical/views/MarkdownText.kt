package io.kognis.tactical.views

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    val annotatedString = buildAnnotatedString {
        var currentIndex = 0
        var isBold = false

        // Simple Regex for **text**
        val regex = Regex("\\*\\*(.*?)\\*\\*")
        
        // Find all matches
        val matches = regex.findAll(text)
        
        var lastMatchEnd = 0
        
        for (match in matches) {
            // Append text before the bold part
            append(text.substring(lastMatchEnd, match.range.first))
            
            // Append bold text
            val startStyle = length
            append(match.groupValues[1])
            addStyle(
                style = SpanStyle(fontWeight = FontWeight.Bold),
                start = startStyle,
                end = length
            )
            
            lastMatchEnd = match.range.last + 1
        }
        
        // Append remaining text
        append(text.substring(lastMatchEnd))
    }
    
    Text(
        text = annotatedString,
        modifier = modifier,
        color = color,
        lineHeight = 22.sp
    )
}
