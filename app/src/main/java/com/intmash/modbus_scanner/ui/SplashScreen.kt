package com.intmash.modbus_scanner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intmash.modbus_scanner.R

@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo_intmash),
                contentDescription = "Логотип",
                modifier = Modifier
                    .size(120.dp)
                    .padding(8.dp)
            )

            Text(
                text = "INTMASH",
                fontSize = 36.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Modbus Scanner",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            CircularProgressIndicator()
        }
    }
}