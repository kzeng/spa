package com.seamlesspassage.spa.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
fun AdminExitDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val pwd = remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理员验证") },
        text = {
            OutlinedTextField(
                value = pwd.value,
                onValueChange = { pwd.value = it },
                // (默认 123321)
                label = { Text("请输入密码") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(keyboardType = KeyboardType.Password)
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(pwd.value) }) { Text("确认退出") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("取消") }
        }
    )
}
